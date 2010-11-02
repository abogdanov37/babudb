/*
 * Copyright (c) 2009-2010, Jan Stender, Bjoern Kolbeck, Mikael Hoegqvist,
 *                     Felix Hupfeld, Felix Langner, Zuse Institute Berlin
 * 
 * Licensed under the BSD License, see LICENSE file for details.
 * 
 */
package org.xtreemfs.babudb;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;

import org.xtreemfs.babudb.api.BabuDB;
import org.xtreemfs.babudb.api.Checkpointer;
import org.xtreemfs.babudb.api.Database;
import org.xtreemfs.babudb.api.DatabaseManager;
import org.xtreemfs.babudb.api.SnapshotManager;
import org.xtreemfs.babudb.api.exceptions.BabuDBException;
import org.xtreemfs.babudb.api.exceptions.BabuDBException.ErrorCode;
import org.xtreemfs.babudb.config.BabuDBConfig;
import org.xtreemfs.babudb.conversion.AutoConverter;
import org.xtreemfs.babudb.log.DiskLogIterator;
import org.xtreemfs.babudb.log.DiskLogger;
import org.xtreemfs.babudb.log.LogEntry;
import org.xtreemfs.babudb.log.LogEntryException;
import org.xtreemfs.babudb.lsmdb.CheckpointerImpl;
import org.xtreemfs.babudb.lsmdb.DBConfig;
import org.xtreemfs.babudb.lsmdb.DatabaseImpl;
import org.xtreemfs.babudb.lsmdb.DatabaseManagerImpl;
import org.xtreemfs.babudb.lsmdb.InsertRecordGroup;
import org.xtreemfs.babudb.lsmdb.LSMDBWorker;
import org.xtreemfs.babudb.lsmdb.LSMDatabase;
import org.xtreemfs.babudb.lsmdb.LSN;
import org.xtreemfs.babudb.snapshots.SnapshotConfig;
import org.xtreemfs.babudb.snapshots.SnapshotManagerImpl;
import org.xtreemfs.foundation.LifeCycleThread;
import org.xtreemfs.foundation.logging.Logging;

import static org.xtreemfs.babudb.BabuDBFactory.*;

/**
 * BabuDB main class.
 * 
 * <p>
 * <b>Please use the {@link BabuDBFactory} to generate instances of
 * {@link BabuDB}.</b>
 * </p>
 * 
 * @author bjko
 * @author flangner
 * @author stenjan
 * 
 */
public class BabuDBImpl implements BabuDBInternal {
    
    /**
     * the disk logger is used to write InsertRecordGroups persistently to disk
     */
    private DiskLogger                   logger;
    
    private LSMDBWorker[]                worker;
    
    /**
     * Checkpointer thread for automatic checkpointing
     */
    private CheckpointerImpl             dbCheckptr;
    
    /**
     * the component that manages database snapshots
     */
    private final SnapshotManagerImpl    snapshotManager;
    
    /**
     * the component that manages databases
     */
    private final DatabaseManagerImpl    databaseManager;
    
    /**
     * All necessary parameters to run the BabuDB.
     */
    private final BabuDBConfig           configuration;
    
    /**
     * File used to store meta-informations about DBs.
     */
    private final DBConfig               dbConfigFile;
    
    /**
     * Flag that shows the replication if babuDB is running at the moment.
     */
    private final AtomicBoolean          stopped = new AtomicBoolean(true);
    
    /**
     * Threads used within the plugins. They will be stopped when BabuDB shuts
     * down. 
     */
    private final List<LifeCycleThread>  plugins = 
        new Vector<LifeCycleThread>();
    
    static {
        //ReusableBuffer.enableAutoFree(true);
        //BufferPool.enableStacktraceRecording(false);
    }
    
    /**
     * Initializes the basic components of the BabuDB database system.
     * 
     * @param configuration
     * @throws BabuDBException
     */
    BabuDBImpl(BabuDBConfig configuration) throws BabuDBException {
      
        this.configuration = configuration;
        this.databaseManager = new DatabaseManagerImpl(this);
        this.dbConfigFile = new DBConfig(this);
        this.snapshotManager = new SnapshotManagerImpl(this);
        this.dbCheckptr = new CheckpointerImpl(this); 
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.BabuDB#init(StaticInitialization staticInit)
     */
    @Override
    public void init(final StaticInitialization staticInit) throws BabuDBException {
        snapshotManager.init();
        
        /* TODO
        try {
            if (conf instanceof ReplicationConfig)
                new FileIO((ReplicationConfig) conf).replayBackupFiles();
        } catch (IOException io) {
            Logging.logMessage(Logging.LEVEL_ERROR, this, "Could not retrieve the "
                + "slave backup files, because: ", io.getMessage());
        }
        */
        // determine the LSN from which to start the log replay
        
        // to be able to recover from crashes during checkpoints, it is
        // necessary to start with the smallest LSN found on disk
        LSN dbLsn = null;
        for (Database db : databaseManager.getDatabaseList()) {
            if (dbLsn == null)
                dbLsn = ((DatabaseImpl) db).getLSMDB().getOndiskLSN();
            else {
                LSN onDiskLSN = ((DatabaseImpl) db).getLSMDB().getOndiskLSN();
                if (!(LSMDatabase.NO_DB_LSN.equals(dbLsn) || LSMDatabase.NO_DB_LSN.equals(onDiskLSN)))
                    dbLsn = dbLsn.compareTo(onDiskLSN) < 0 ? dbLsn : onDiskLSN;
            }
        }
        if (dbLsn == null) {
            // empty database
            dbLsn = new LSN(0, 0);
        } else {
            // need next LSN which is onDisk + 1
            dbLsn = new LSN(dbLsn.getViewId() == 0 ? 1 : dbLsn.getViewId(), dbLsn.getSequenceNo() + 1);
        }
        
        Logging.logMessage(Logging.LEVEL_INFO, this, "starting log replay at LSN %s", dbLsn);
        LSN nextLSN = replayLogs(dbLsn);
        if (dbLsn.compareTo(nextLSN) > 0) {
            nextLSN = dbLsn;
        }
        Logging.logMessage(Logging.LEVEL_INFO, this, "log replay done, using LSN: " + nextLSN);
        
        // set up the replication service
        /* TODO
        try {
            if (conf instanceof ReplicationConfig) {
                this.replicationManager = new ReplicationManagerImpl(this);
                Logging.logMessage(Logging.LEVEL_INFO, this, "BabuDB will use replication");
            } else {
                this.replicationManager = null;
                Logging.logMessage(Logging.LEVEL_INFO, this, "BabuDB will not use replication");
            }
        } catch (Exception e) {
            Logging.logError(Logging.LEVEL_ERROR, this, e);
            throw new BabuDBException(ErrorCode.REPLICATION_FAILURE, e.getMessage());
        }
        */
        
        // set up and start the disk logger
        try {
            this.logger = new DiskLogger(configuration.getDbLogDir(), nextLSN.getViewId(), nextLSN.getSequenceNo(),
                configuration.getSyncMode(), configuration.getPseudoSyncWait(),
                configuration.getMaxQueueLength() * configuration.getNumThreads());
            this.logger.start();
        } catch (IOException ex) {
            throw new BabuDBException(ErrorCode.IO_ERROR, "cannot start database operations logger", ex);
        }
        
        if (configuration.getNumThreads() > 0) {
            worker = new LSMDBWorker[configuration.getNumThreads()];
            for (int i = 0; i < configuration.getNumThreads(); i++) {
                worker[i] = new LSMDBWorker(logger, i, (configuration.getPseudoSyncWait() > 0), configuration
                        .getMaxQueueLength());
                worker[i].start();
            }
        } else {
            // number of workers is 0 => requests will be responded directly.
            assert (configuration.getNumThreads() == 0);
            
            worker = null;
        }
        
        if (dbConfigFile.isConversionRequired())
            AutoConverter.completeConversion(this);
        
        // initialize and start the checkpointer; this has to be separated from
        // the instantiation because the instance has to be there when the log
        // is replayed
        dbCheckptr.init(logger, configuration.getCheckInterval(), 
                                configuration.getMaxLogfileSize());
        dbCheckptr.start();
        
        if (staticInit != null) {
            staticInit.initialize(databaseManager, snapshotManager);
        }
        
        this.stopped.set(false);
        /* TODO
        if (replicationManager != null)
            replicationManager.initialize();
            */
        
        Logging.logMessage(Logging.LEVEL_INFO, this, "BabuDB for Java is running " + "(version "
            + BABUDB_VERSION + ")");
    }
    
    /**
     * REPLICATION Stops the BabuDB for an initial Load. This is necessary if
     * the replication has copied the onDiskData from a master- participant due
     * a {@link LoadLogic} run.
     */
    public void stop() {
        synchronized (stopped) {
            if (stopped.get())
                return;
            
            if (worker != null)
                for (LSMDBWorker w : worker)
                    w.shutdown();
            
            logger.shutdown();
            dbCheckptr.shutdown();
            try {
                logger.waitForShutdown();
                if (worker != null)
                    for (LSMDBWorker w : worker)
                        w.waitForShutdown();
                
                dbCheckptr.waitForShutdown();
            } catch (InterruptedException ex) { /* ignored */
            }
            this.stopped.set(true);
            Logging.logMessage(Logging.LEVEL_INFO, this, "BabuDB has been stopped by the Replication.");
            
        }
    }
    
    /**
     * <p>
     * Needed for the initial load process of the babuDB, done by the
     * replication.
     * </p>
     * 
     * @throws BabuDBException
     * @return the next LSN.
     */
    public LSN restart() throws BabuDBException {
        synchronized (stopped) {
            if (!stopped.get())
                throw new BabuDBException(ErrorCode.IO_ERROR, "BabuDB has to be stopped before!");
            
            databaseManager.reset();
            
            dbCheckptr = new CheckpointerImpl(this);
            
            // determine the LSN from which to start the log replay
            
            // to be able to recover from crashes during checkpoints, it is
            // necessary to start with the smallest LSN found on disk
            LSN dbLsn = null;
            for (Database db : databaseManager.getDatabaseList()) {
                if (dbLsn == null)
                    dbLsn = ((DatabaseImpl) db).getLSMDB().getOndiskLSN();
                else {
                    LSN onDiskLSN = ((DatabaseImpl) db).getLSMDB().getOndiskLSN();
                    if (!(LSMDatabase.NO_DB_LSN.equals(dbLsn) || LSMDatabase.NO_DB_LSN.equals(onDiskLSN)))
                        dbLsn = dbLsn.compareTo(onDiskLSN) < 0 ? dbLsn : onDiskLSN;
                }
            }
            if (dbLsn == null) {
                // empty database
                dbLsn = new LSN(0, 0);
            } else {
                // need next LSN which is onDisk + 1
                dbLsn = new LSN(dbLsn.getViewId(), dbLsn.getSequenceNo() + 1);
            }
            
            Logging.logMessage(Logging.LEVEL_INFO, this, "starting log replay");
            LSN nextLSN = replayLogs(dbLsn);
            if (dbLsn.compareTo(nextLSN) > 0)
                nextLSN = dbLsn;
            Logging.logMessage(Logging.LEVEL_INFO, this, "log replay done, using LSN: " + nextLSN);
            
            try {
                logger = new DiskLogger(configuration.getDbLogDir(), nextLSN.getViewId(), nextLSN
                        .getSequenceNo(), configuration.getSyncMode(), configuration.getPseudoSyncWait(),
                    configuration.getMaxQueueLength() * configuration.getNumThreads());
                logger.start();
            } catch (IOException ex) {
                throw new BabuDBException(ErrorCode.IO_ERROR, "cannot start database operations logger", ex);
            }
            
            if (configuration.getNumThreads() > 0) {
                worker = new LSMDBWorker[configuration.getNumThreads()];
                for (int i = 0; i < configuration.getNumThreads(); i++) {
                    worker[i] = new LSMDBWorker(logger, i, (configuration.getPseudoSyncWait() > 0),
                        configuration.getMaxQueueLength());
                    worker[i].start();
                }
            } else {
                // number of workers is 0 => requests will be responded
                // directly.
                assert (configuration.getNumThreads() == 0);
                
                worker = null;
            }
            
            dbCheckptr.init(logger, configuration.getCheckInterval(), configuration.getMaxLogfileSize());
            dbCheckptr.start();
            
            Logging.logMessage(Logging.LEVEL_INFO, this, "BabuDB for Java is " + "running (version "
                + BABUDB_VERSION + ")");
            
            this.stopped.set(false);
            return new LSN(nextLSN.getViewId(), nextLSN.getSequenceNo() - 1L);
        }
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.BabuDB#shutdown()
     */
    @Override
    public void shutdown() throws BabuDBException {
        
        Logging.logMessage(Logging.LEVEL_INFO, this, "shutting down BabuDB ...");
        
        if (worker != null)
            for (LSMDBWorker w : worker)
                w.shutdown();
        
        // stop the plugin threads
        for (LifeCycleThread p : plugins) {
            p.shutdown();
            try {
                p.waitForShutdown();
            } catch (Exception e) {
                throw new BabuDBException(ErrorCode.BROKEN_PLUGIN, 
                        e.getMessage(), e.getCause());
            }
        }
        
        try {
            
            // shut down the logger; this keeps insertions from being completed
            logger.shutdown();
            logger.waitForShutdown();
            
            // complete checkpoint before shutdown
            dbCheckptr.shutdown();
            dbCheckptr.waitForShutdown();
            
            databaseManager.shutdown();
            snapshotManager.shutdown();
            
            if (worker != null) {
                for (LSMDBWorker w : worker)
                    w.waitForShutdown();
                
                Logging.logMessage(Logging.LEVEL_DEBUG, this, "%d worker threads shut down successfully",
                    worker.length);
            }
            
        } catch (InterruptedException ex) {
        }
        Logging.logMessage(Logging.LEVEL_INFO, this, "BabuDB shutdown complete.");
    }
    
    /**
     * NEVER USE THIS EXCEPT FOR UNIT TESTS! Kills the database.
     */
    @SuppressWarnings("deprecation")
    public void __test_killDB_dangerous() {
        try {
            logger.stop();
            if (worker != null)
                for (LSMDBWorker w : worker)
                    w.stop();
            dbCheckptr.stop();
            
        } catch (IllegalMonitorStateException ex) {
            // we will probably get that when we kill a thread because we do
            // evil stuff here ;-)
        }
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.BabuDB#getCheckpointer()
     */
    @Override
    public Checkpointer getCheckpointer() {
        return dbCheckptr;
    }
    
    /**
     * Returns a reference to the disk logger. The disk logger should not be
     * accessed by applications.
     * 
     * @return a reference to the disk logger
     */
    public DiskLogger getLogger() {
        return logger;
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.BabuDB#getDatabaseManager()
     */
    @Override
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.BabuDBInternal#getConfig()
     */
    public BabuDBConfig getConfig() {
        return configuration;
    }
    
    /**
     * Returns the complete path to the database configuration file.
     * 
     * @return the path to the DB-configuration-file, if available,
     *         <code>null</code> otherwise.
     */
    public String getDBConfigPath() {
        String result = configuration.getBaseDir() + configuration.getDbCfgFile();
        File f = new File(result);
        if (f.exists())
            return result;
        return null;
    }
    
    /**
     * Replays the database operations log.
     * 
     * @param from
     *            - LSN to replay the logs from.
     * @return the LSN to assign to the next operation
     * @throws BabuDBException
     */
    private LSN replayLogs(LSN from) throws BabuDBException {
        try {
            File f = new File(configuration.getDbLogDir());
            File[] logFiles = f.listFiles(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return name.endsWith(".dbl");
                }
            });
            
            DiskLogIterator it = new DiskLogIterator(logFiles, from);
            LSN nextLSN = null;
            
            // apply log entries to databases ...
            while (it.hasNext()) {
                LogEntry le = null;
                try {
                    le = it.next();
                    
                    switch (le.getPayloadType()) {
                    
                    case LogEntry.PAYLOAD_TYPE_INSERT:
                        InsertRecordGroup ai = InsertRecordGroup.deserialize(le.getPayload());
                        databaseManager.insert(ai);
                        break;
                    
                    case LogEntry.PAYLOAD_TYPE_SNAP:
                        ObjectInputStream oin = null;
                        try {
                            oin = new ObjectInputStream(new ByteArrayInputStream(le.getPayload().array()));
                            // deserialize the snapshot configuration
                            int dbId = oin.readInt();
                            SnapshotConfig snap = (SnapshotConfig) oin.readObject();
                            
                            Database db = databaseManager.getDatabase(dbId);
                            if (db == null)
                                break;
                            
                            snapshotManager.createPersistentSnapshot(db.getName(), snap, false);
                        } catch (Exception e) {
                            throw new BabuDBException(ErrorCode.IO_ERROR,
                                "Snapshot could not be recovered because: " + e.getMessage(), e);
                        } finally {
                            if (oin != null)
                                oin.close();
                        }
                        break;
                    
                    case LogEntry.PAYLOAD_TYPE_SNAP_DELETE:

                        byte[] payload = le.getPayload().array();
                        int offs = payload[0];
                        String dbName = new String(payload, 1, offs);
                        String snapName = new String(payload, offs + 1, payload.length - offs - 1);
                        
                        snapshotManager.deletePersistentSnapshot(dbName, snapName, false);
                        break;
                    
                    default: // create, copy and delete are skipped
                        break;
                    }
                    
                    // set LSN
                    nextLSN = new LSN(le.getViewId(), le.getLogSequenceNo() + 1L);
                } finally {
                    if (le != null)
                        le.free();
                }
                
            }
            
            it.destroy();
            
            if (nextLSN != null) {
                return nextLSN;
            } else {
                return new LSN(1, 1);
            }
            
        } catch (IOException ex) {
            throw new BabuDBException(ErrorCode.IO_ERROR,
                "cannot load database operations log, file might be corrupted", ex);
        } catch (Exception ex) {
            if (ex.getCause() instanceof LogEntryException) {
                throw new BabuDBException(ErrorCode.IO_ERROR,
                    "corrupted/incomplete log entry in database operations log", ex.getCause());
            } else
                throw new BabuDBException(ErrorCode.IO_ERROR,
                    "corrupted/incomplete log entry in database operations log", ex);
        }
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.BabuDB#getSnapshotManager()
     */
    @Override
    public SnapshotManager getSnapshotManager() {
        return snapshotManager;
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.BabuDBInternal#getDBConfigFile()
     */
    @Override
    public DBConfig getDBConfigFile() {
        return dbConfigFile;
    }
    
    /**
     * Returns the number of worker threads.
     * 
     * @return the number of worker threads.
     */
    public int getWorkerCount() {
        if (worker == null)
            return 0;
        return worker.length;
    }
    
    /**
     * 
     * @param dbId
     * @return a worker Thread, responsible for the DB given by its ID.
     */
    public LSMDBWorker getWorker(int dbId) {
        if (worker == null)
            return null;
        return worker[dbId % worker.length];
    }
    
    /** TODO
     * @return true, if replication runs in slave-mode, false otherwise.
    
    public void slaveCheck() throws BabuDBException {
        if (replicationManager != null && replicationManager.isInitialized()
            && !replicationManager.isMaster()) {
            throw new BabuDBException(ErrorCode.NO_ACCESS, slaveProtectionMsg);
        }
    }
     */
}