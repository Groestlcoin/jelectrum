package jelectrum.db;

import com.google.protobuf.ByteString;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import jelectrum.Config;
import jelectrum.EventLog;
import jelectrum.TimeRecord;
import jelectrum.db.lobstack.LobstackDB;
import jelectrum.db.slopbucket.SlopbucketDB;
import org.bitcoinj.core.Sha256Hash;
import org.junit.Assert;

public class GrindTest
{
  private static final long ITEMS_TO_ADD = 100L * 1000000L;
  private static final int ITEMS_PER_PUT = 10000;
  private static final int THREADS = 8;

  private AtomicInteger items_saved= new AtomicInteger(0);

  public static void main(String args[]) throws Exception
  {
    String name =args[0];
    new GrindTest(name);
    

  }

  DBMap db_map;
  Semaphore done_sem;
  LinkedBlockingQueue<String> queue;
  EventLog log;

  public GrindTest(String name)
    throws Exception
  {

    DB db = null;
    Config conf = new Config("jelly-test.conf");
    log =new EventLog(System.out);

    if (name.equals("lobstack"))
    {
      db = new LobstackDB(null, conf);
    }
    if (name.equals("slopbucket"))
    {
      db = new SlopbucketDB(conf, log);
    }
    if (name.equals("rocksdb"))
    {
      db = new jelectrum.db.rocksdb.JRocksDB(conf, log);
    }

    Assert.assertNotNull("DB must not be null", db);

    log.log("Selected DB: " + name);

    db_map = db.openMap("grindtest");

    new RateThread(15000).start();

    performTest(name);


  }

  private void performTest(String name)
    throws Exception
  {
    done_sem = new Semaphore(0);
    queue = new LinkedBlockingQueue<>();
    int runs = (int)ITEMS_TO_ADD / ITEMS_PER_PUT;
    for(int i=0; i<runs; i++)
    {
      queue.put("" + i);
    }

    log.log("Adding " + ITEMS_TO_ADD + " in groups of " + ITEMS_PER_PUT);


    long total_start = System.nanoTime();

    for(int i=0; i<THREADS; i++) new GrindThread().start();

    done_sem.acquire(runs);
    System.out.println();

    long total_end = System.nanoTime();

    double sec = (total_end - total_start) / 1e9;

    DecimalFormat df = new DecimalFormat("0.000");

    log.log("Run "+ name +" done in " + df.format(sec));


  }


  public class GrindThread extends Thread
  {
    public GrindThread()
    {
      setName("GrindThread");
      setDaemon(true);
    }

    public void run()
    {
      Random rnd = new Random();
      while(true)
      {
        try
        {
          queue.take();

          Map<String, ByteString> put_map = new HashMap<String, ByteString>(ITEMS_PER_PUT*2, 0.75f);

          for(int i=0; i< ITEMS_PER_PUT; i++)
          {
            put_map.put(randomHash(rnd).toString(), randomByteString(rnd));
          }
          db_map.putAll(put_map);
          done_sem.release();
          items_saved.addAndGet(ITEMS_PER_PUT);
        }
        catch(Throwable e)
        {
          e.printStackTrace();

        }
      }
    }
  }

  public static Sha256Hash randomHash(Random rnd)
  {
    byte[] b=new byte[32];
    rnd.nextBytes(b);
    return Sha256Hash.wrap(b);
  }
  public static ByteString randomByteString(Random rnd)
  {
    byte[] b=new byte[100];
    rnd.nextBytes(b);

    return ByteString.copyFrom(b);
  }
    public class RateThread extends Thread
    {
        private long delay;
        private String name;

        public RateThread(long delay)
        {
            this.delay = delay;
            setDaemon(true);
            setName("RateThread");

        }

        public void run()
        {
            long items = 0;
            long last_run = System.currentTimeMillis();
            DecimalFormat df =new DecimalFormat("0.000");
            while(true)
            {
                TimeRecord tr = new TimeRecord();
                TimeRecord.setSharedRecord(tr);
                System.gc();
                try{Thread.sleep(delay);}catch(Exception e){}

                long now = System.currentTimeMillis();
                long items_now = items_saved.get();

                double sec = (now - last_run) / 1000.0;

                double items_rate = (items_now - items) / sec;
                String rate_log = "Rate: " + df.format(items_rate) + "/s";

                log.log(rate_log);

                tr.printReport(System.out);

                items = items_now;
                last_run= now;
            }
        }
    }


}
