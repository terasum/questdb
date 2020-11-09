package io.questdb.cairo.replication;

import java.io.Closeable;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.replication.ReplicationSlaveManager.SlaveWriter;
import io.questdb.std.FilesFacade;
import io.questdb.std.IntList;
import io.questdb.std.IntObjHashMap;
import io.questdb.std.ObjList;
import io.questdb.std.Unsafe;

public class ReplicationStreamReceiver implements Closeable {
    enum IOContextResult {
        NEEDS_READ, NEEDS_BACKOFF_RETRY, NEEDS_WRITE
    }

    private final FilesFacade ff;
    private final ReplicationSlaveManager recvMgr;
    private final IntList tableIds = new IntList();
    private final IntObjHashMap<TableDetails> tableDetailsByMasterTableId = new IntObjHashMap<>();
    private final ObjList<TableDetails> tableDetailsCache = new ObjList<>();
    private long fd = -1;
    private long frameHeaderAddress;
    private long frameHeaderOffset;
    private long frameHeaderRemaining;

    private byte frameType;
    private long frameDataNBytesRemaining;
    private int masterTableId;
    private SlaveWriter slaveWriter;

    private long frameFirstTimestamp;
    private long frameMappingAddress;
    private long frameMappingSize;
    private long frameMappingOffset;

    private int dataFrameColumnIndex;
    private long dataFrameColumnOffset;

    private boolean readyToCommit;

    public ReplicationStreamReceiver(CairoConfiguration configuration, ReplicationSlaveManager recvMgr) {
        this.ff = configuration.getFilesFacade();
        this.recvMgr = recvMgr;
        frameHeaderAddress = Unsafe.malloc(TableReplicationStreamHeaderSupport.MAX_HEADER_SIZE);
        fd = -1;
    }

    public void of(long fd) {
        this.fd = fd;
        readyToCommit = false;
        resetReading();
    }

    private void resetReading() {
        frameHeaderOffset = 0;
        frameHeaderRemaining = TableReplicationStreamHeaderSupport.MIN_HEADER_SIZE;
        frameType = TableReplicationStreamHeaderSupport.FRAME_TYPE_UNKNOWN;
        frameMappingAddress = 0;
        slaveWriter = null;
    }

    IOContextResult handleIO() {
        if (!readyToCommit) {
            return handleRead();
        } else {
            return handleWrite();
        }
    }

    IOContextResult handleRead() {
        while (frameHeaderRemaining > 0) {
            long nRead = ff.read(fd, frameHeaderAddress, frameHeaderRemaining, frameHeaderOffset);
            if (nRead == -1) {
                // TODO Disconnected while reading header
                throw new RuntimeException();
            }
            frameHeaderOffset += nRead;
            frameHeaderRemaining -= nRead;
            if (frameHeaderRemaining > 0) {
                return IOContextResult.NEEDS_READ;
            }

            if (frameType == TableReplicationStreamHeaderSupport.FRAME_TYPE_UNKNOWN) {
                // decode the generic header
                decodeGenericHeader();
                if (frameHeaderRemaining > 0)
                    continue;
            }

            switch (frameType) {
                case TableReplicationStreamHeaderSupport.FRAME_TYPE_DATA_FRAME: {
                    handleDataFrameHeader();
                    break;
                }

                case TableReplicationStreamHeaderSupport.FRAME_TYPE_END_OF_BLOCK: {
                    handleEndOfBlockHeader();
                    return IOContextResult.NEEDS_WRITE;
                }

                case TableReplicationStreamHeaderSupport.FRAME_TYPE_COMMIT_BLOCK: {
                    handleCommitBlock();
                    return IOContextResult.NEEDS_READ;
                }

                default:
                    assert false;
            }
        }

        long nRead = ff.read(fd, frameMappingAddress, frameDataNBytesRemaining, frameMappingOffset);
        if (nRead == -1) {
            // TODO Disconnected mid stream
            throw new RuntimeException();
        }
        frameDataNBytesRemaining -= nRead;
        if (frameDataNBytesRemaining == 0) {
            if (slaveWriter.unmap(dataFrameColumnIndex, frameMappingAddress, frameMappingSize)) {
                handleReadyToCommit();
            }
            slaveWriter = null;
            resetReading();
        }
        return IOContextResult.NEEDS_READ;
    }

    IOContextResult handleWrite() {
        long nWritten = ff.write(fd, frameHeaderAddress, frameHeaderRemaining, frameHeaderOffset);
        if (nWritten == -1) {
            // TODO Disconnected mid stream
            throw new RuntimeException();
        }
        frameHeaderRemaining -= nWritten;
        frameHeaderOffset += nWritten;
        if (frameHeaderRemaining == 0) {
            readyToCommit = false;
            resetReading();
            return IOContextResult.NEEDS_READ;
        }

        return IOContextResult.NEEDS_WRITE;
    }

    private void handleEndOfBlockHeader() {
        if (frameDataNBytesRemaining != 0) {
            // TODO Received junk in the header
            throw new RuntimeException();
        }
        int nFrames = Unsafe.getUnsafe().getInt(frameHeaderAddress + TableReplicationStreamHeaderSupport.OFFSET_EOB_N_FRAMES_SENT);
        if (slaveWriter.markBlockNFrames(nFrames)) {
            handleReadyToCommit();
        }
    }

    private void handleCommitBlock() {
        if (frameDataNBytesRemaining != 0) {
            // TODO Received junk in the header
            throw new RuntimeException();
        }
        slaveWriter.commit();
        resetReading();
    }

    private void handleDataFrameHeader() {
        // TODO deal with column top
        frameFirstTimestamp = Unsafe.getUnsafe().getLong(frameHeaderAddress + TableReplicationStreamHeaderSupport.OFFSET_DF_FIRST_TIMESTAMP);
        dataFrameColumnIndex = Unsafe.getUnsafe().getInt(frameHeaderAddress + TableReplicationStreamHeaderSupport.OFFSET_DF_COLUMN_INDEX);
        dataFrameColumnOffset = Unsafe.getUnsafe().getLong(frameHeaderAddress + TableReplicationStreamHeaderSupport.OFFSET_DF_DATA_OFFSET);

        frameMappingAddress = slaveWriter.mapColumnData(frameFirstTimestamp, dataFrameColumnIndex, dataFrameColumnOffset, frameDataNBytesRemaining);
        frameMappingSize = frameDataNBytesRemaining;
        frameMappingOffset = 0;
    }

    private void decodeGenericHeader() {
        assert frameHeaderRemaining == 0;
        frameType = Unsafe.getUnsafe().getByte(frameHeaderAddress + TableReplicationStreamHeaderSupport.OFFSET_FRAME_TYPE);
        if (frameType > TableReplicationStreamHeaderSupport.FRAME_TYPE_MAX_ID || frameType < TableReplicationStreamHeaderSupport.FRAME_TYPE_MIN_ID) {
            // TODO Received junk frame type
            throw new RuntimeException();
        }
        frameHeaderRemaining = TableReplicationStreamHeaderSupport.getFrameHeaderSize(frameType) - frameHeaderOffset;
        frameDataNBytesRemaining = Unsafe.getUnsafe().getInt(frameHeaderAddress + TableReplicationStreamHeaderSupport.OFFSET_FRAME_SIZE) - frameHeaderOffset
                - frameHeaderRemaining;
        if (frameDataNBytesRemaining < 0) {
            // TODO Received junk in the header
            throw new RuntimeException();
        }
        masterTableId = Unsafe.getUnsafe().getInt(frameHeaderAddress + TableReplicationStreamHeaderSupport.OFFSET_MASTER_TABLE_ID);
        slaveWriter = getSlaveWriter(masterTableId);
    }

    private void handleReadyToCommit() {
        frameHeaderRemaining = TableReplicationStreamHeaderSupport.SCR_HEADER_SIZE;
        Unsafe.getUnsafe().putInt(frameHeaderAddress + TableReplicationStreamHeaderSupport.OFFSET_FRAME_SIZE, (int) frameHeaderRemaining);
        Unsafe.getUnsafe().putByte(frameHeaderAddress + TableReplicationStreamHeaderSupport.OFFSET_FRAME_TYPE, TableReplicationStreamHeaderSupport.FRAME_TYPE_SLAVE_COMMIT_READY);
        Unsafe.getUnsafe().putInt(frameHeaderAddress + TableReplicationStreamHeaderSupport.OFFSET_MASTER_TABLE_ID, masterTableId);
        frameHeaderOffset = 0;
        readyToCommit = true;
    }

    private SlaveWriter getSlaveWriter(int masterTableId) {
        SlaveWriter slaveWriter;
        TableDetails tableDetails = tableDetailsByMasterTableId.get(masterTableId);
        if (null == tableDetails) {
            slaveWriter = recvMgr.getSlaveWriter(masterTableId);
            tableDetails = newTableDetails().of(masterTableId, slaveWriter);
            tableDetailsByMasterTableId.put(masterTableId, tableDetails);
            tableIds.add(masterTableId);
        } else {
            slaveWriter = tableDetails.getSlaveWriter();
        }
        return slaveWriter;
    }

    public void clear() {
        if (fd != -1) {
            for (int id = 0, sz = tableIds.size(); id < sz; id++) {
                releaseTableDetails(tableDetailsByMasterTableId.get(tableIds.get(id)));
            }
            tableIds.clear();
            tableDetailsByMasterTableId.clear();
            fd = -1;
        }
    }

    @Override
    public void close() {
        if (0 != frameHeaderAddress) {
            clear();
            Unsafe.free(frameHeaderAddress, TableReplicationStreamHeaderSupport.MAX_HEADER_SIZE);
            frameHeaderAddress = 0;
        }
    }

    private TableDetails newTableDetails() {
        int sz = tableDetailsCache.size();
        if (sz > 0) {
            sz--;
            TableDetails tableDetails = tableDetailsCache.get(sz);
            tableDetailsCache.remove(sz);
            return tableDetails;
        }
        return new TableDetails();
    }

    private void releaseTableDetails(TableDetails tableDetails) {
        tableDetails.clear();
        tableDetailsCache.add(tableDetails);
    }

    private class TableDetails implements Closeable {
        private int masterTableId;
        private SlaveWriter slaveWriter;

        private TableDetails of(int masterTableId, SlaveWriter slaveWriter) {
            assert this.slaveWriter == null;
            this.masterTableId = masterTableId;
            this.slaveWriter = slaveWriter;
            return this;
        }

        private SlaveWriter getSlaveWriter() {
            return slaveWriter;
        }

        private void clear() {
            if (null != slaveWriter) {
                recvMgr.releaseSlaveWriter(masterTableId, slaveWriter);
                slaveWriter = null;
            }
        }

        @Override
        public void close() {
            clear();
        }

    }
}