package jelectrum;

import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.NetworkParameters;
import java.nio.ByteBuffer;


public class SerializedTransaction implements java.io.Serializable
{
    private transient Transaction tx;

    private byte[] bytes;

    private long saved_time=0L;
    private static int VERSION_MAGIC=1839345191;

    public SerializedTransaction(Transaction tx)
    {
        this.tx = tx;
        saved_time=System.currentTimeMillis();

        byte[] tx_bytes = tx.bitcoinSerialize();
        bytes = new byte[tx_bytes.length + 8 + 4];
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        bb.put(tx_bytes);
        bb.putInt(VERSION_MAGIC);
        bb.putLong(saved_time);
        
    }
    public SerializedTransaction(byte[] bytes)
    {
      this.bytes = bytes;

      ByteBuffer bb = ByteBuffer.wrap(bytes);
      bb.position(bytes.length - 12);
      int ver = bb.getInt();
      if (ver == VERSION_MAGIC)
      {
        saved_time = bb.getLong();
      }
      else
      {
        saved_time = 0L;
      }
    }

    public byte[] getBytes()
    {
      return bytes;
    }

    public Transaction getTx(NetworkParameters params)
    {
        if (tx != null) return tx;
        tx = new Transaction(params, bytes);
        return tx;
        
    }

    public static Transaction scrubTransaction(NetworkParameters params, Transaction trans)
    {
        return new Transaction(params, trans.bitcoinSerialize());
    }

    public long getSavedTime()
    {
        return saved_time;
    }


}
