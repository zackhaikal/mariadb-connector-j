package org.mariadb.jdbc;


import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.sql.XAConnection;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.Assert.*;

public class DistributedTransaction extends BaseTest {

    MariaDbDataSource dataSource;

    /**
     * Initialisation.
     */
    public DistributedTransaction() {
        dataSource = new MariaDbDataSource();
        dataSource.setServerName(hostname);
        dataSource.setPortNumber(port);
        dataSource.setDatabaseName(database);
        dataSource.setUser(username);
        dataSource.setPassword(password);
    }

    @BeforeClass()
    public static void initClass() throws SQLException {
        createTable("xatable", "i int", "ENGINE=InnoDB");
    }

    @Before
    public void checkSupported() throws SQLException {
        requireMinimumVersion(5, 0);
    }

    Xid newXid() {
        return new MariaDbXid(1, UUID.randomUUID().toString().getBytes(), UUID.randomUUID().toString().getBytes());
    }

    Xid newXid(Xid branchFrom) {
        return new MariaDbXid(1, branchFrom.getGlobalTransactionId(), UUID.randomUUID().toString().getBytes());
    }

    /**
     * 2 phase commit , with either commit or rollback at the end.
     *
     * @param doCommit must commit
     * @throws Exception exception
     */
    void test2PhaseCommit(boolean doCommit) throws Exception {


        int connectionNumber = 1;

        Xid parentXid = newXid();
        Connection[] connections = new Connection[connectionNumber];
        XAConnection[] xaConnections = new XAConnection[connectionNumber];
        XAResource[] xaResources = new XAResource[connectionNumber];
        Xid[] xids = new Xid[connectionNumber];

        try {

            for (int i = 0; i < connectionNumber; i++) {
                xaConnections[i] = dataSource.getXAConnection();
                connections[i] = xaConnections[i].getConnection();
                xaResources[i] = xaConnections[i].getXAResource();
                xids[i] = newXid(parentXid);
            }

            for (int i = 0; i < connectionNumber; i++) {
                xaResources[i].start(xids[i], XAResource.TMNOFLAGS);
            }

            for (int i = 0; i < connectionNumber; i++) {
                connections[i].createStatement().executeUpdate("INSERT INTO xatable VALUES (" + i + ")");
            }

            for (int i = 0; i < connectionNumber; i++) {
                xaResources[i].end(xids[i], XAResource.TMSUCCESS);
            }

            for (int i = 0; i < connectionNumber; i++) {
                xaResources[i].prepare(xids[i]);
            }

            for (int i = 0; i < connectionNumber; i++) {
                if (doCommit) {
                    xaResources[i].commit(xids[i], false);
                } else {
                    xaResources[i].rollback(xids[i]);
                }
            }


            // check the completion
            ResultSet rs = sharedConnection.createStatement().executeQuery("SELECT * from xatable order by i");
            if (doCommit) {
                for (int i = 0; i < connectionNumber; i++) {
                    rs.next();
                    assertEquals(rs.getInt(1), i);
                }
            } else {

                assertFalse(rs.next());
            }
            rs.close();
        } finally {
            for (int i = 0; i < connectionNumber; i++) {
                try {
                    if (xaConnections[i] != null) {
                        xaConnections[i].close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Test
    public void testCommit() throws Exception {
        test2PhaseCommit(true);
    }

    @Test
    public void testRollback() throws Exception {
        test2PhaseCommit(false);
    }

    @Test
    public void testRecover() throws Exception {
        XAConnection xaConnection = dataSource.getXAConnection();
        try {
            Connection connection = xaConnection.getConnection();
            Xid xid = newXid();
            XAResource xaResource = xaConnection.getXAResource();
            xaResource.start(xid, XAResource.TMNOFLAGS);
            connection.createStatement().executeQuery("SELECT 1");
            xaResource.end(xid, XAResource.TMSUCCESS);
            xaResource.prepare(xid);
            Xid[] recoveredXids = xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
            assertTrue(recoveredXids != null);
            assertTrue(recoveredXids.length > 0);
            boolean found = false;

            for (Xid x : recoveredXids) {
                if (x != null && x.equals(xid)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found);
        } finally {
            xaConnection.close();
        }
    }

    @Test
    public void resumeAndJoinTest() throws Exception {
        Connection conn1 = null;
        MariaDbDataSource ds = new MariaDbDataSource();
        ds.setUrl(connU);
        ds.setDatabaseName(database);
        ds.setUser(username);
        ds.setPassword(password);
        ds.setPort(port);
        XAConnection xaConn1 = null;
        Xid xid = newXid();
        try {
            xaConn1 = ds.getXAConnection();
            XAResource xaRes1 = xaConn1.getXAResource();
            conn1 = xaConn1.getConnection();
            xaRes1.start(xid, XAResource.TMNOFLAGS);
            conn1.createStatement().executeQuery("SELECT 1");
            xaRes1.end(xid, XAResource.TMSUCCESS);
            xaRes1.start(xid, XAResource.TMRESUME);
            conn1.createStatement().executeQuery("SELECT 1");
            xaRes1.end(xid, XAResource.TMSUCCESS);
            xaRes1.commit(xid, true);
            xaConn1.close();

            xaConn1 = ds.getXAConnection();
            xaRes1 = xaConn1.getXAResource();
            conn1 = xaConn1.getConnection();
            xaRes1.start(xid, XAResource.TMNOFLAGS);
            conn1.createStatement().executeQuery("SELECT 1");
            xaRes1.end(xid, XAResource.TMSUCCESS);
            try {
                xaRes1.start(xid, XAResource.TMJOIN);
                assertTrue(false); // without pinGlobalTxToPhysicalConnection=true
            } catch (XAException xaex) {
                if (xaConn1 != null) {
                    xaConn1.close();
                }
            }

            ds.setProperties("pinGlobalTxToPhysicalConnection=true");
            xaConn1 = ds.getXAConnection();
            xaRes1 = xaConn1.getXAResource();
            conn1 = xaConn1.getConnection();
            xaRes1.start(xid, XAResource.TMNOFLAGS);
            conn1.createStatement().executeQuery("SELECT 1");
            xaRes1.end(xid, XAResource.TMSUCCESS);
            xaRes1.start(xid, XAResource.TMJOIN);
            conn1.createStatement().executeQuery("SELECT 1");
            xaRes1.end(xid, XAResource.TMSUCCESS);
            xaRes1.commit(xid, true);
        } finally {
            if (xaConn1 != null) {
                xaConn1.close();
            }
        }
    }
}