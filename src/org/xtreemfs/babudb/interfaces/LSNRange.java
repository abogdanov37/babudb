package org.xtreemfs.babudb.interfaces;

import org.xtreemfs.babudb.*;
import java.util.HashMap;
import org.xtreemfs.babudb.interfaces.utils.*;
import org.xtreemfs.include.foundation.oncrpc.utils.ONCRPCBufferWriter;
import org.xtreemfs.include.common.buffer.ReusableBuffer;




public class LSNRange implements org.xtreemfs.babudb.interfaces.utils.Serializable
{
    public static final int TAG = 1026;

    
    public LSNRange() { viewId = 0; sequenceStart = 0; sequenceEnd = 0; }
    public LSNRange( int viewId, long sequenceStart, long sequenceEnd ) { this.viewId = viewId; this.sequenceStart = sequenceStart; this.sequenceEnd = sequenceEnd; }
    public LSNRange( Object from_hash_map ) { viewId = 0; sequenceStart = 0; sequenceEnd = 0; this.deserialize( from_hash_map ); }
    public LSNRange( Object[] from_array ) { viewId = 0; sequenceStart = 0; sequenceEnd = 0;this.deserialize( from_array ); }

    public int getViewId() { return viewId; }
    public void setViewId( int viewId ) { this.viewId = viewId; }
    public long getSequenceStart() { return sequenceStart; }
    public void setSequenceStart( long sequenceStart ) { this.sequenceStart = sequenceStart; }
    public long getSequenceEnd() { return sequenceEnd; }
    public void setSequenceEnd( long sequenceEnd ) { this.sequenceEnd = sequenceEnd; }

    // Object
    public String toString()
    {
        return "LSNRange( " + Integer.toString( viewId ) + ", " + Long.toString( sequenceStart ) + ", " + Long.toString( sequenceEnd ) + " )";
    }

    // Serializable
    public int getTag() { return 1026; }
    public String getTypeName() { return "org::xtreemfs::babudb::interfaces::LSNRange"; }

    public void deserialize( Object from_hash_map )
    {
        this.deserialize( ( HashMap<String, Object> )from_hash_map );
    }
        
    public void deserialize( HashMap<String, Object> from_hash_map )
    {
        this.viewId = ( from_hash_map.get( "viewId" ) instanceof Integer ) ? ( ( Integer )from_hash_map.get( "viewId" ) ).intValue() : ( ( Long )from_hash_map.get( "viewId" ) ).intValue();
        this.sequenceStart = ( from_hash_map.get( "sequenceStart" ) instanceof Integer ) ? ( ( Integer )from_hash_map.get( "sequenceStart" ) ).longValue() : ( ( Long )from_hash_map.get( "sequenceStart" ) ).longValue();
        this.sequenceEnd = ( from_hash_map.get( "sequenceEnd" ) instanceof Integer ) ? ( ( Integer )from_hash_map.get( "sequenceEnd" ) ).longValue() : ( ( Long )from_hash_map.get( "sequenceEnd" ) ).longValue();
    }
    
    public void deserialize( Object[] from_array )
    {
        this.viewId = ( from_array[0] instanceof Integer ) ? ( ( Integer )from_array[0] ).intValue() : ( ( Long )from_array[0] ).intValue();
        this.sequenceStart = ( from_array[1] instanceof Integer ) ? ( ( Integer )from_array[1] ).longValue() : ( ( Long )from_array[1] ).longValue();
        this.sequenceEnd = ( from_array[2] instanceof Integer ) ? ( ( Integer )from_array[2] ).longValue() : ( ( Long )from_array[2] ).longValue();        
    }

    public void deserialize( ReusableBuffer buf )
    {
        viewId = buf.getInt();
        sequenceStart = buf.getLong();
        sequenceEnd = buf.getLong();
    }

    public Object serialize()
    {
        HashMap<String, Object> to_hash_map = new HashMap<String, Object>();
        to_hash_map.put( "viewId", new Integer( viewId ) );
        to_hash_map.put( "sequenceStart", new Long( sequenceStart ) );
        to_hash_map.put( "sequenceEnd", new Long( sequenceEnd ) );
        return to_hash_map;        
    }

    public void serialize( ONCRPCBufferWriter writer ) 
    {
        writer.putInt( viewId );
        writer.putLong( sequenceStart );
        writer.putLong( sequenceEnd );
    }
    
    public int calculateSize()
    {
        int my_size = 0;
        my_size += ( Integer.SIZE / 8 );
        my_size += ( Long.SIZE / 8 );
        my_size += ( Long.SIZE / 8 );
        return my_size;
    }


    private int viewId;
    private long sequenceStart;
    private long sequenceEnd;    

}

