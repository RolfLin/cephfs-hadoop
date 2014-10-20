// -*- mode:Java; tab-width:2; c-basic-offset:2; indent-tabs-mode:t -*- 

/**
 *
 * Licensed under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * 
 * Implements the Hadoop FS interfaces to allow applications to store
 * files in Ceph.
 */
package org.apache.hadoop.fs.ceph;


import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSInputStream;

import com.ceph.fs.CephMount;

/**
 * <p>
 * An {@link FSInputStream} for a CephFileSystem and corresponding
 * Ceph instance.
 */
public class CephInputStream extends FSInputStream {
  private static final Log LOG = LogFactory.getLog(CephInputStream.class);
  private boolean closed;

  private int fileHandle;

  private long fileLength;

  private CephFS ceph;

  private byte[] buffer;
  private int bufPos = 0;
  private int bufValid = 0;
  private long cephPos = 0;

  /**
   * Create a new CephInputStream.
   * @param conf The system configuration. Unused.
   * @param fh The filehandle provided by Ceph to reference.
   * @param flength The current length of the file. If the length changes
   * you will need to close and re-open it to access the new data.
   */
  public CephInputStream(Configuration conf, CephFS cephfs,
      int fh, long flength, int bufferSize) {
    // Whoever's calling the constructor is responsible for doing the actual ceph_open
    // call and providing the file handle.
    fileLength = flength;
    fileHandle = fh;
    closed = false;
    ceph = cephfs;
    buffer = new byte[1<<21];
    LOG.debug(
        "CephInputStream constructor: initializing stream with fh " + fh
        + " and file length " + flength);
      
  }

  /** Ceph likes things to be closed before it shuts down,
   * so closing the IOStream stuff voluntarily in a finalizer is good
   */
  protected void finalize() throws Throwable {
    try {
      if (!closed) {
        close();
      }
    } finally {
      super.finalize();
    }
  }

  private synchronized boolean fillBuffer() throws IOException {
    bufValid = ceph.read(fileHandle, buffer, buffer.length, -1);
    bufPos = 0;
    if (bufValid < 0) {
      int err = bufValid;

      bufValid = 0;
      // attempt to reset to old position. If it fails, too bad.
      ceph.lseek(fileHandle, cephPos, CephMount.SEEK_SET);
      throw new IOException("Failed to fill read buffer! Error code:" + err);
    }
    cephPos += bufValid;
    return (bufValid != 0);
  }

  /*
   * Get the current position of the stream.
   */
  public synchronized long getPos() throws IOException {
    return cephPos - bufValid + bufPos;
  }

  /**
   * Find the number of bytes remaining in the file.
   */
  @Override
  public synchronized int available() throws IOException {
    return (int) (fileLength - getPos());
  }

  public synchronized void seek(long targetPos) throws IOException {
    LOG.trace(
        "CephInputStream.seek: Seeking to position " + targetPos + " on fd "
        + fileHandle);
    if (targetPos > fileLength) {
      throw new IOException(
          "CephInputStream.seek: failed seek to position " + targetPos
          + " on fd " + fileHandle + ": Cannot seek after EOF " + fileLength);
    }
    long oldPos = cephPos;

    cephPos = ceph.lseek(fileHandle, targetPos, CephMount.SEEK_SET);
    bufValid = 0;
    bufPos = 0;
    if (cephPos < 0) {
      cephPos = oldPos;
      throw new IOException("Ceph failed to seek to new position!");
    }
  }

  /**
   * Failovers are handled by the Ceph code at a very low level;
   * if there are issues that can be solved by changing sources
   * they'll be dealt with before anybody even tries to call this method!
   * @return false.
   */
  public synchronized boolean seekToNewSource(long targetPos) {
    return false;
  }
    
  /**
   * Read a byte from the file.
   * @return the next byte.
   */
  @Override
  public synchronized int read() throws IOException {
    LOG.trace(
        "CephInputStream.read: Reading a single byte from fd " + fileHandle
        + " by calling general read function");

    byte result[] = new byte[1];

    if (getPos() >= fileLength) {
      return -1;
    }
    if (-1 == read(result, 0, 1)) {
      return -1;
    }
    if (result[0] < 0) {
      return 256 + (int) result[0];
    } else {
      return result[0];
    }
  }

  /**
   * Read a specified number of bytes from the file into a byte[].
   * @param buf the byte array to read into.
   * @param off the offset to start at in the file
   * @param len the number of bytes to read
   * @return 0 if successful, otherwise an error code.
   * @throws IOException on bad input.
   */
  @Override
  public synchronized int read(byte buf[], int off, int len)
    throws IOException {
    LOG.trace(
        "CephInputStream.read: Reading " + len + " bytes from fd " + fileHandle);
      
    if (closed) {
      throw new IOException(
          "CephInputStream.read: cannot read " + len + " bytes from fd "
          + fileHandle + ": stream closed");
    }
			
    // ensure we're not past the end of the file
    if (getPos() >= fileLength) {
      LOG.debug(
          "CephInputStream.read: cannot read " + len + " bytes from fd "
          + fileHandle + ": current position is " + getPos()
          + " and file length is " + fileLength);
				
      return -1;
    }

    int totalRead = 0;
    int initialLen = len;
    int read;

    do {
      read = Math.min(len, bufValid - bufPos);
      try {
        System.arraycopy(buffer, bufPos, buf, off, read);
      } catch (IndexOutOfBoundsException ie) {
        throw new IOException(
            "CephInputStream.read: Indices out of bounds:" + "read length is "
            + len + ", buffer offset is " + off + ", and buffer size is "
            + buf.length);
      } catch (ArrayStoreException ae) {
        throw new IOException(
            "Uh-oh, CephInputStream failed to do an array"
                + "copy due to type mismatch...");
      } catch (NullPointerException ne) {
        throw new IOException(
            "CephInputStream.read: cannot read " + len + "bytes from fd:"
            + fileHandle + ": buf is null");
      }
      bufPos += read;
      len -= read;
      off += read;
      totalRead += read;
    } while (len > 0 && fillBuffer());

    LOG.trace(
        "CephInputStream.read: Reading " + initialLen + " bytes from fd "
        + fileHandle + ": succeeded in reading " + totalRead + " bytes");
    return totalRead;
  }

  /**
   * Close the CephInputStream and release the associated filehandle.
   */
  @Override
  public void close() throws IOException {
    LOG.trace("CephOutputStream.close:enter");
    if (!closed) {
      ceph.close(fileHandle);

      closed = true;
      LOG.trace("CephOutputStream.close:exit");
    }
  }
}
