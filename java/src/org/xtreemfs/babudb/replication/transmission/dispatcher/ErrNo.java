/*
 * Copyright (c) 2009, Jan Stender, Bjoern Kolbeck, Mikael Hoegqvist,
 *                     Felix Hupfeld, Felix Langner, Zuse Institute Berlin
 * 
 * Licensed under the BSD License, see LICENSE file for details.
 * 
 */
package org.xtreemfs.babudb.replication.transmission.dispatcher;

/**
 * Error message IDs.
 * 
 * @since 05/02/2009
 * @author flangner
 */

public final class ErrNo {
    public static final int SECURITY            = 1;
    public static final int LOG_REMOVED         = 2;
    public static final int FILE_UNAVAILABLE    = 3;
    public static final int BUSY                = 4;
    public static final int INTERNAL_ERROR      = 5;
    
    public static final int UNKNOWN             = 99;
    public static final int SERVICE_UNAVAILABLE = 404;
}