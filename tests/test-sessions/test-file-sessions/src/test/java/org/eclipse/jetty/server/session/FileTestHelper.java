//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.session;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.util.Map;

import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.IO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileTestHelper
 */
public class FileTestHelper
{
    File _tmpDir;

    public  FileTestHelper()
        throws Exception
    {

        _tmpDir = File.createTempFile("file", "test");
        _tmpDir.delete();
        _tmpDir.mkdirs();
        _tmpDir.deleteOnExit();
    }

    public void teardown()
    {
        IO.delete(_tmpDir);
        _tmpDir = null;
    }

    public void assertStoreDirEmpty(boolean isEmpty)
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());
        String[] files = _tmpDir.list();
        if (isEmpty)
        {
            if (files != null)
                assertEquals(0, files.length);
        }
        else
        {
            assertNotNull(files);
            assertFalse(files.length == 0);
        }
    }

    public File getFile(String sessionId)
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());
        String[] files = _tmpDir.list();
        assertNotNull(files);
        String fname = null;
        for (String name : files)
        {
            int i = name.lastIndexOf('_');
            if (i < 0 || i == name.length() - 1)
                continue;
            String id = name.substring(i + 1);
            if (id.equals(sessionId))
            {
                fname = name;
                break;
            }
        }

        if (fname != null)
            return new File(_tmpDir, fname);
        return null;
    }

    public void assertSessionExists(String sessionId, boolean exists)
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());
        String[] files = _tmpDir.list();
        assertNotNull(files);
        if (exists)
            assertFalse(files.length == 0);
        boolean found = false;
        for (String name : files)
        {
            int i = name.lastIndexOf('_');
            if (i < 0)
                continue;
            String id = name.substring(i);
            if (id.equals(sessionId))
            {
                found = true;
                break;
            }
        }
        if (exists)
            assertTrue(found);
        else
            assertFalse(found);
    }

    public void assertFileExists(String filename, boolean exists)
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());
        File file = new File(_tmpDir, filename);
        if (exists)
            assertTrue(file.exists());
        else
            assertFalse(file.exists());
    }

    public void createFile(String filename)
        throws IOException
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());

        File file = new File(_tmpDir, filename);
        Files.deleteIfExists(file.toPath());
        file.createNewFile();
    }

    public void createFile(String id, String contextPath, String vhost,
                                  String lastNode, long created, long accessed,
                                  long lastAccessed, long maxIdle, long expiry,
                                  long cookieSet, Map<String, Object> attributes)
        throws Exception
    {
        String filename = "" + expiry + "_" + contextPath + "_" + vhost + "_" + id;
        File file = new File(_tmpDir, filename);
        try (FileOutputStream fos = new FileOutputStream(file, false);
             DataOutputStream out = new DataOutputStream(fos))
        {
            out.writeUTF(id);
            out.writeUTF(contextPath);
            out.writeUTF(vhost);
            out.writeUTF(lastNode);
            out.writeLong(created);
            out.writeLong(accessed);
            out.writeLong(lastAccessed);
            out.writeLong(cookieSet);
            out.writeLong(expiry);
            out.writeLong(maxIdle);

            if (attributes != null)
            {
                SessionData tmp = new SessionData(id, contextPath, vhost, created, accessed, lastAccessed, maxIdle);
                ObjectOutputStream oos = new ObjectOutputStream(out);
                SessionData.serializeAttributes(tmp, oos);
            }
        }
    }

    public boolean checkSessionPersisted(SessionData data)
        throws Exception
    {
        String filename = "" + data.getExpiry() + "_" + data.getContextPath() + "_" + data.getVhost() + "_" + data.getId();
        File file = new File(_tmpDir, filename);
        assertTrue(file.exists());

        try (FileInputStream in = new FileInputStream(file);
             DataInputStream di = new DataInputStream(in))
        {
            String id = di.readUTF();
            String contextPath = di.readUTF();
            String vhost = di.readUTF();
            String lastNode = di.readUTF();
            long created = di.readLong();
            long accessed = di.readLong();
            long lastAccessed = di.readLong();
            long cookieSet = di.readLong();
            long expiry = di.readLong();
            long maxIdle = di.readLong();

            assertEquals(data.getId(), id);
            assertEquals(data.getContextPath(), contextPath);
            assertEquals(data.getVhost(), vhost);
            assertEquals(data.getLastNode(), lastNode);
            assertEquals(data.getCreated(), created);
            assertEquals(data.getAccessed(), accessed);
            assertEquals(data.getLastAccessed(), lastAccessed);
            assertEquals(data.getCookieSet(), cookieSet);
            assertEquals(data.getExpiry(), expiry);
            assertEquals(data.getMaxInactiveMs(), maxIdle);

            SessionData tmp = new SessionData(id, contextPath, vhost, created, accessed, lastAccessed, maxIdle);
            ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(di);
            SessionData.deserializeAttributes(tmp, ois);

            //same number of attributes
            assertEquals(data.getAllAttributes().size(), tmp.getAllAttributes().size());
            //same keys
            assertTrue(data.getKeys().equals(tmp.getAllAttributes().keySet()));
            //same values
            for (String name : data.getKeys())
            {
                assertTrue(data.getAttribute(name).equals(tmp.getAttribute(name)));
            }
        }

        return true;
    }

    public void deleteFile(String sessionId)
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());
        String[] files = _tmpDir.list();
        assertNotNull(files);
        assertFalse(files.length == 0);
        String filename = null;
        for (String name : files)
        {
            if (name.contains(sessionId))
            {
                filename = name;
                break;
            }
        }
        if (filename != null)
        {
            File f = new File(_tmpDir, filename);
            assertTrue(f.delete());
        }
    }

    public FileSessionDataStoreFactory newSessionDataStoreFactory()
    {
        FileSessionDataStoreFactory storeFactory = new FileSessionDataStoreFactory();
        storeFactory.setStoreDir(_tmpDir);
        return storeFactory;
    }
}
