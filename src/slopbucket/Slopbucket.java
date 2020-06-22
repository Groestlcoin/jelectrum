package slopbucket;

import jelectrum.EventLog;

import java.io.File;
import java.io.RandomAccessFile;

import java.util.TreeMap;
import java.nio.channels.FileChannel;
import java.util.Map;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.ByteBuffer;

import java.util.HashSet;
import java.util.Set;

import com.google.protobuf.ByteString;
import org.junit.Assert;
import duckutil.TimeRecord;

import bloomtime.DeterministicStream;
import java.nio.channels.FileChannel;


public class Slopbucket
{
  private EventLog log;
  private static final long SEGMENT_FILE_SIZE=Integer.MAX_VALUE;
  
  public static final int MAX_TROUGHS=64;
  public static final int MAX_TROUGH_NAME_LEN=64;

  private static final int LOCATION_VERSION=0;
  private static final int LOCATION_NEXT_FREE=LOCATION_VERSION+8;
  private static final int LOCATION_TROUGH_TABLE_START=LOCATION_NEXT_FREE+8;
  private static final int LOCATION_FIRST_FREE=LOCATION_TROUGH_TABLE_START+ (MAX_TROUGH_NAME_LEN + 8) * MAX_TROUGHS;

  private static final int LOCATION_HASH_MAX=0;
  private static final int LOCATION_HASH_ITEMS=LOCATION_HASH_MAX+4;
  private static final int LOCATION_HASH_NEXT=LOCATION_HASH_ITEMS+4;
  private static final int LOCATION_HASH_START=LOCATION_HASH_NEXT+8;
 
  private static final int HASH_INITAL_SIZE=64*1024;
  private static final int HASH_MULTIPLCATION=64;
  private static final double HASH_FULL=0.5;
  
  private Object ptr_lock = new Object();

  private Map<Integer, MappedByteBuffer> open_buffers;
  private Map<String, Integer> trough_map;
  

  private File slop_file;
  private FileChannel file_channel; 


  public Slopbucket(File slop_file, EventLog log)
    throws IOException
  {
    this.log = log;
    this.slop_file = slop_file;
    RandomAccessFile raf = new RandomAccessFile(slop_file, "rw");

    file_channel = raf.getChannel();
    open_buffers = new TreeMap<>();

  }

  private long getCurrentWriteLocation()
  {
    MappedByteBuffer mbb = getBufferMap(0);
    long v;
    v = mbb.getLong((int)LOCATION_NEXT_FREE);
    if (v == 0) return LOCATION_FIRST_FREE;
    return v;

  }
  private void setCurrentWriteLocation(long v)
  {
    MappedByteBuffer mbb = getBufferMap(0);
    mbb.putLong((int)LOCATION_NEXT_FREE, v);
  }

  public Map<String, Integer> getTroughMap()
  {
    if (trough_map != null) return trough_map;

    Map<String, Integer> m = loadTroughMap();
    trough_map = m;
    return m;
    
  }

  protected Map<String, Integer> loadTroughMap()
  {
    TreeMap<String,Integer> map = new TreeMap<>();

    MappedByteBuffer mbb = getBufferMap(0);
    synchronized(mbb)
    {
      for(int i=0; i<MAX_TROUGHS; i++)
      {
        mbb.position( (int)(LOCATION_TROUGH_TABLE_START + (8 + MAX_TROUGH_NAME_LEN) * i));
        long ptr = mbb.getLong();
        byte[] name = new byte[MAX_TROUGH_NAME_LEN];
        mbb.get(name);
        int len =0;
        for(int j=0; (j<MAX_TROUGH_NAME_LEN) && (name[j] != 0); j++)
        {
          len++;
        }
        if (len > 0)
        {
          String name_str = new String(name, 0, len);
          map.put(name_str, i);
        }
        else
        {
          map.put("__FREE", i);
        }
      }
    }
    return map;
  }

  public void addTrough(String name)
  {
    if (name.length() > MAX_TROUGH_NAME_LEN) throw new RuntimeException("OVER MAX NAME LENGTH");

    Map<String, Integer> troughs = getTroughMap();

    if(troughs.containsKey(name)) return;

    if (!troughs.containsKey("__FREE"))
    throw new RuntimeException("TOO MANY TROUGHS");

    int trough_idx = troughs.get("__FREE");

    long hash_loc = makeNewHashTable(HASH_INITAL_SIZE);

    MappedByteBuffer mbb = getBufferMap(0);
    synchronized(mbb)
    {
      mbb.position( (int) (LOCATION_TROUGH_TABLE_START + (8 + MAX_TROUGH_NAME_LEN) * trough_idx));
      mbb.putLong(hash_loc);
      mbb.put(name.getBytes());
    }
    trough_map = null;
  }

  public long getTroughPtr(String name)
  {
    Map<String, Integer> troughs = getTroughMap();
    if (!troughs.containsKey(name))
    {
      throw new RuntimeException("Unable to find trough: " + name);
    }
    int idx=troughs.get(name);
    MappedByteBuffer mbb = getBufferMap(0);
    synchronized(mbb)
    {
      mbb.position( (int) (LOCATION_TROUGH_TABLE_START + (8 + MAX_TROUGH_NAME_LEN) * idx) );
      return mbb.getLong();
    }
  }

  protected long makeNewHashTable(int items)
  {
    items = Math.min(items, (int)(SEGMENT_FILE_SIZE / 8 - 2));
    long hash_loc = allocateSpace((int) (items * 8 + LOCATION_HASH_START));
    writeInt(hash_loc + LOCATION_HASH_MAX, items);
    log.log("Making new hash table of size: " + items + " at " + hash_loc);
    return hash_loc;
  }

  protected void writeLong(long position, long value)
  {
    int file = (int) (position / SEGMENT_FILE_SIZE);
    int offset_in_file = (int) (position % SEGMENT_FILE_SIZE);
    MappedByteBuffer mbb = getBufferMap(file);
    synchronized(mbb)
    {
      mbb.position(offset_in_file);
      mbb.putLong(value);
    }
  }
  protected void writeInt(long position, int value)
  {
    int file = (int) (position / SEGMENT_FILE_SIZE);
    int offset_in_file = (int) (position % SEGMENT_FILE_SIZE);
    MappedByteBuffer mbb = getBufferMap(file);
    synchronized(mbb)
    {
      mbb.position(offset_in_file);
      mbb.putInt(value);
    }
  }

  protected long allocateSpace(int size)
  {

    synchronized(ptr_lock)
    {
        long loc = getCurrentWriteLocation();

        long new_end = loc + size + 4;
        //If this would go into the next segment, just go to next segment
        if ((loc / SEGMENT_FILE_SIZE) < (new_end / SEGMENT_FILE_SIZE))
        {
          loc = (new_end / SEGMENT_FILE_SIZE) * SEGMENT_FILE_SIZE;
        }

        long current_write_location = loc + size + 4;
        setCurrentWriteLocation(current_write_location);

        return loc;
    }

  }

  protected MappedByteBuffer getBufferMap(int idx)
  {
    synchronized(open_buffers)
    {
      if (open_buffers.containsKey(idx))
      {
        return open_buffers.get(idx);
      }

      MappedByteBuffer mbb = openFileInternal(idx);
      open_buffers.put(idx, mbb);
      return mbb;
    }
  }

  protected MappedByteBuffer openFileInternal(int idx)
  {
    try
    {
      long pos = idx * SEGMENT_FILE_SIZE;
      return file_channel.map(FileChannel.MapMode.READ_WRITE, pos, SEGMENT_FILE_SIZE);
    }
    catch(java.io.IOException e)
    {
      throw new RuntimeException(e);
    }
  }

  public synchronized void putKeyValue(String trough_name, ByteString key, ByteString value)
  {
    long t1 = System.nanoTime();
    long pos = getTroughPtr(trough_name);
    TimeRecord.record(t1, "slop_get_trough_ptr");

    putKeyValueTable(pos, new RecordEntry(key, value));
  }

  public synchronized ByteString getKeyValue(String trough_name, ByteString key)
  {
    long pos = getTroughPtr(trough_name);
    RecordEntry re = getKeyValueTable(pos, key);
    if (re == null) return null;
    return re.getValue();

  }

  public synchronized void addListEntry(String trough_name, ByteString key, ByteString value)
  {
    long trough_pos = getTroughPtr(trough_name);
    RecordEntry prev_entry = getKeyValueTable(trough_pos, key);
    long prev_location = 0;
    if (prev_entry != null)
    {
      prev_location = prev_entry.getDataLoc();
    }

    byte[] new_data_buff = new byte[8 + value.size()];

    ByteBuffer bb = ByteBuffer.wrap(new_data_buff);
    bb.putLong(prev_location);
    value.copyTo(new_data_buff, 8);
    ByteString new_data = ByteString.copyFrom(new_data_buff);

    RecordEntry re = new RecordEntry(key, new_data);
    re.storeItem(0L);


    putKeyValueTable(trough_pos, re);
    
  }
  public synchronized Set<ByteString> getList(String trough_name, ByteString key)
  {
    long trough_pos = getTroughPtr(trough_name);
    RecordEntry re = getKeyValueTable(trough_pos, key);

    HashSet<ByteString> set = new HashSet<ByteString>();
    while(re != null)
    {
      ByteString val_obj = re.getValue();

      ByteString val = val_obj.substring(8);
      set.add(val);
      
      long location = val_obj.asReadOnlyByteBuffer().getLong();

      re = null;
      if (location > 0)
      {
        re = new RecordEntry(location);
      }

    }
    return set;
  }

  protected RecordEntry getKeyValueTable(long table_pos, ByteString key)
  {
    int hash_file = (int) (table_pos / SEGMENT_FILE_SIZE);
    MappedByteBuffer hash_mbb = getBufferMap(hash_file);
    int file_offset = (int) (table_pos % SEGMENT_FILE_SIZE);


    int max;
    int items;
    long next_ptr;

    synchronized(hash_mbb)
    {
      hash_mbb.position(file_offset + (int) LOCATION_HASH_MAX); 
      max = hash_mbb.getInt();
      items = hash_mbb.getInt();
      next_ptr = hash_mbb.getLong();
    }

    Assert.assertTrue("Max " + max + " items " + items + " table at " + table_pos + " file " + hash_file + " file offset " + file_offset ,max > items);
    Assert.assertTrue(max > 4);
    Assert.assertTrue(items >= 0);

    int hash = key.hashCode();
    int loc = Math.abs(hash % max);
    if (loc < 0) loc = 0;
    //DeterministicStream det_stream = new DeterministicStream(key);
    //int loc = det_stream.nextInt(max);

    while(true)
    {
      Assert.assertTrue(loc >= 0);
      Assert.assertTrue(loc < max);
      synchronized(hash_mbb)
      {
        hash_mbb.position(file_offset + LOCATION_HASH_START + loc * 8);
        long ptr = hash_mbb.getLong();
      

        if (ptr != 0)
        {
          RecordEntry re = new RecordEntry(ptr);
          if (re.getKey().equals(key)) return re;
        }
        if (ptr == 0)
        {
          if (next_ptr != 0)
          {
            return getKeyValueTable(next_ptr, key); 
          }
          else
          {
            return null;
          }
        }
      }
    
      //loc = det_stream.nextInt(max);
      loc = (loc + 131 ) % max;
    }

  }

  protected void putKeyValueTable(long table_pos, RecordEntry put_re)
  {
    long t1=System.nanoTime();
    int hash_file = (int) (table_pos / SEGMENT_FILE_SIZE);
    MappedByteBuffer hash_mbb = getBufferMap(hash_file);
    int file_offset = (int) (table_pos % SEGMENT_FILE_SIZE);
    

    int max;
    int items;
    long next_ptr;

    synchronized(hash_mbb)
    {
      hash_mbb.position(file_offset + (int) LOCATION_HASH_MAX); 
      max = hash_mbb.getInt();
      items = hash_mbb.getInt();
      next_ptr = hash_mbb.getLong();
    }

    Assert.assertTrue("Max " + max + " items " + items + " table at " + table_pos + " file " + hash_file + " file offset " + file_offset ,max > items);
    Assert.assertTrue(max > 4);
    Assert.assertTrue(items >= 0);

    //DeterministicStream det_stream = new DeterministicStream(key);
    //int loc = det_stream.nextInt(max);
    int hash = put_re.getKey().hashCode();
    int loc = Math.abs(hash % max);
    if (loc < 0) loc = 0;

    double full = (double) items / (double) max;



    while(true)
    {
      Assert.assertTrue(loc >= 0);
      Assert.assertTrue(loc < max);
      synchronized(hash_mbb)
      {
        long t1_check = System.nanoTime();
        hash_mbb.position(file_offset + LOCATION_HASH_START + loc * 8);
        long ptr = hash_mbb.getLong();
        TimeRecord.record(t1_check, "slop_get_ptr");

        if ((ptr == 0) && (full >= HASH_FULL))
        { 
          // It isn't here and the hash is full, move on to next table

          if (next_ptr == 0)
          {
            next_ptr = makeNewHashTable(max * HASH_MULTIPLCATION);
            hash_mbb.position(file_offset + (int) LOCATION_HASH_NEXT);
            hash_mbb.putLong(next_ptr);
          }
          TimeRecord.record(t1, "slop_put_key_value_table_rec");
          putKeyValueTable(next_ptr, put_re);
          return;
     
        }
        RecordEntry re = null;
        if (ptr != 0)
        {
          re = new RecordEntry(ptr);
          if (!re.getKey().equals(put_re.getKey()))
          {
            re = null;
          }
        }
        if ((ptr == 0) || (re!=null))
        {
          //If we have an empty or a key match
          long data_loc = put_re.storeItem(ptr);

          hash_mbb.position(file_offset + LOCATION_HASH_START + loc * 8);
          hash_mbb.putLong(data_loc);

          if (ptr == 0)
          {
            hash_mbb.position(file_offset + LOCATION_HASH_ITEMS);
            items++;
            hash_mbb.putInt(items);
          }
          TimeRecord.record(t1, "slop_put_key_value_table_add");
          return;
        }
      }
      
      //loc = det_stream.nextInt(max);
      loc = (loc + 131 ) % max;
    }

  }

  private Map<String, Long> getStats(String trough_name)
  {
    Map<String, Long> map = new TreeMap<String, Long>();

    map.put("tables",0L);
    map.put("items",0L);
    map.put("key_size",0L);
    map.put("data_size",0L);
    long pos = getTroughPtr(trough_name);

    getTableStats(pos, map);

    return map;

  }
  private void getTableStats(long table_pos, Map<String, Long> map)
  {
    map.put("tables", map.get("tables") + 1);

    int hash_file = (int) (table_pos / SEGMENT_FILE_SIZE);
    MappedByteBuffer hash_mbb = getBufferMap(hash_file);
    int file_offset = (int) (table_pos % SEGMENT_FILE_SIZE);

    int max;
    int items;
    long next_ptr;

    synchronized(hash_mbb)
    {
      hash_mbb.position(file_offset + (int) LOCATION_HASH_MAX); 
      max = hash_mbb.getInt();
      items = hash_mbb.getInt();
      next_ptr = hash_mbb.getLong();

      hash_mbb.position(file_offset + (int) LOCATION_HASH_START);
      for(int i=0; i<max; i++)
      {
        long ptr = hash_mbb.getLong(file_offset + LOCATION_HASH_START + i*8);
        if (ptr != 0)
        {
          RecordEntry re = new RecordEntry(ptr);
          ByteString key = re.getKey();
          ByteString value = re.getValue();
          map.put("key_size", map.get("key_size") + key.size());
          map.put("data_size", map.get("data_size") + value.size());
          
        }
      }

    }
    map.put("items", map.get("items") + items);

    if (next_ptr != 0)
    {
      getTableStats(next_ptr, map);
    }


  }


  public void printStats()
  {
    for(Map.Entry<String, Integer> me : getTroughMap().entrySet())
    {
      if (!me.getKey().equals("__FREE"))
      {
        System.out.println(me.getKey() + " - " +getStats(me.getKey()));

      }


    }

  }

  public class RecordEntry
  {
    int max_data_size; //Size of entire alloc
    ByteString key;
    ByteString value;
    long data_loc;

    public RecordEntry(long data_loc)
    {
      int file = (int) (data_loc / SEGMENT_FILE_SIZE);
      MappedByteBuffer mbb = getBufferMap(file);
      int offset = (int) (data_loc % SEGMENT_FILE_SIZE);
      synchronized(mbb)
      {
        mbb.position(offset);
        max_data_size = mbb.getInt();

        int sz = mbb.getShort();
        byte[] b = new byte[sz];
        mbb.get(b);
        key = ByteString.copyFrom(b);

        sz = mbb.getInt();
        b = new byte[sz];
        mbb.get(b);
        value = ByteString.copyFrom(b);

      }
      this.data_loc = data_loc;
 
    }
    public RecordEntry(ByteString key, ByteString value)
    {
      data_loc = 0;
      max_data_size = 0;
      this.key = key;
      this.value = value;
    }

    public int getMaxDataSize() { return max_data_size; }
    public ByteString getKey() { return key; }
    public ByteString getValue() { return value; }
    public long getDataLoc() { return data_loc; }
    public int getMinAlloc()
    {
      return 4 + 2 + 4 + key.size() + value.size();
    }

    private void save()
    {
      Assert.assertTrue(max_data_size >= getMinAlloc());
      Assert.assertNotEquals(0, data_loc);
      Assert.assertNotNull(key);
      Assert.assertNotNull(value);


      int file = (int) (data_loc / SEGMENT_FILE_SIZE);
      MappedByteBuffer mbb = getBufferMap(file);
      int offset = (int) (data_loc % SEGMENT_FILE_SIZE);
      synchronized(mbb)
      {
        mbb.position(offset);
        mbb.putInt(max_data_size);
        mbb.putShort((short)key.size());
        mbb.put(key.toByteArray());
        mbb.putInt(value.size());
        mbb.put(value.toByteArray());
      }

    }

    public long storeItem(long prev_ptr)
    {
      //If someone already stored it, just return that location
      if (data_loc > 0) return data_loc;

      //If the prev_ptr is set and can store my item, use that

      if (prev_ptr > 0 )
      {
        RecordEntry prev_entry = new RecordEntry(prev_ptr);
        if (getMinAlloc() <= prev_entry.getMaxDataSize())
        {
          data_loc=prev_ptr;
          max_data_size = prev_entry.getMaxDataSize();
          save();
          return data_loc;
        }
        else
        {
          //If we have already stored it once and it doesn't fit in that space
          //go bigger so hopefully if it changes again it will fit
          max_data_size = getMinAlloc() * 2;
          data_loc = allocateSpace(max_data_size);
          save();
          return data_loc;

        }

      }

      //If not, make a new space
      long t1_save = System.nanoTime();
      max_data_size = getMinAlloc();
      data_loc = allocateSpace(getMinAlloc());
      save();
      TimeRecord.record(t1_save, "slop_save_key_value");
      return data_loc;
    }


  }

  public synchronized void flush(boolean close)
    throws java.io.IOException
  {
    for(MappedByteBuffer mbb : open_buffers.values())
    {
      mbb.force();
    }
    if (close)
    {
      open_buffers.clear();
      file_channel.close();
    }
       
  }
  

}
