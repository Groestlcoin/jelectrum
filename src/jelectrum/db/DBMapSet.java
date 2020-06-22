package jelectrum.db;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import duckutil.TimeRecord;

import org.bitcoinj.core.Sha256Hash;

public abstract class DBMapSet
{
  public abstract void add(String key, Sha256Hash hash);

  /** Override this if the DB can do something better */
  public void addAll(Collection<Map.Entry<String, Sha256Hash> > lst)
  {
    long t1 = System.nanoTime();
    for(Map.Entry<String, Sha256Hash> me : lst)
    {
      add(me.getKey(), me.getValue());
    }
    TimeRecord.record(t1, "db_putset_seq");
  }

  public abstract Set<Sha256Hash> getSet(String key, int max_reply);
}
