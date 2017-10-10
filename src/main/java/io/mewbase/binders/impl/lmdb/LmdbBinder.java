package io.mewbase.binders.impl.lmdb;

import io.mewbase.bson.BsonObject;
import io.mewbase.binders.Binder;


import io.vertx.core.buffer.Buffer;

import org.lmdbjava.CursorIterator;
import org.lmdbjava.Dbi;
import org.lmdbjava.Env;
import org.lmdbjava.Txn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedList;

import java.util.concurrent.*;

import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.nio.ByteBuffer.allocateDirect;
import static org.lmdbjava.CursorIterator.IteratorType.FORWARD;
import static org.lmdbjava.DbiFlags.MDB_CREATE;



/**
 * Created by tim on 29/12/16.
 */
public class LmdbBinder implements Binder {

    private final static Logger logger = LoggerFactory.getLogger(LmdbBinder.class);

    private final String name;

    // In LMDB all transactional ops have thread affinity so they must be executed on the same thread.
    private final ExecutorService stexec;

    private final Env<ByteBuffer> env;
    private Dbi<ByteBuffer> dbi;

    public LmdbBinder(String name, Env<ByteBuffer> env) {
        this.env = env;
        this.name = name;

        // Each Binder has a thread on which it executes blocking calls to the particular dbi
        this.stexec = Executors.newSingleThreadExecutor();

        // create the db if it doesnt exists
        this.dbi =  env.openDbi(name,MDB_CREATE);
        logger.trace("Opened Binder named " + name);
    }


    @Override
    public String getName() {
        return name;
    }

    @Override
    public CompletableFuture<Stream<String>> getIds() {
        return getIdsWithFilter(b -> true);
    }

    @Override
    public CompletableFuture<Stream<String>> getIdsWithFilter(Predicate<BsonObject> filter) {

       CompletableFuture fut = CompletableFuture.supplyAsync( () -> {

            LinkedList<String> matchingIds = new LinkedList<String>();

            try (final Txn<ByteBuffer> txn = env.txnRead()) {
                final CursorIterator<ByteBuffer> cursorItr = dbi.iterate(txn, FORWARD);
                final Iterator<CursorIterator.KeyVal<ByteBuffer>> itr = cursorItr.iterable().iterator();
                boolean hasNext = itr.hasNext();
                txn.reset();

                while (hasNext) {
                    txn.renew();
                    final CursorIterator.KeyVal<ByteBuffer> kv = itr.next();
                    // Copy bytes from LMDB managed memory to vert.x buffers
                    final Buffer keyBuffer = Buffer.buffer(kv.key().remaining());
                    keyBuffer.setBytes(0, kv.key());
                    final Buffer valueBuffer = Buffer.buffer(kv.val().remaining());
                    valueBuffer.setBytes(0, kv.val());
                    hasNext = itr.hasNext(); // iterator makes reference to the txn
                    txn.reset(); // got data so release the txn
                    final BsonObject doc = new BsonObject(valueBuffer);
                    final String docId = new String(keyBuffer.getBytes());
                    if (filter.test(doc)) matchingIds.addLast(docId);
                }
            }
            return matchingIds.stream();
        }, stexec);
        return fut;
    }


    @Override
    public CompletableFuture<BsonObject> get(String id) {
        CompletableFuture fut = CompletableFuture.supplyAsync( () -> {
            // in order to do a read we have to do it under a txn so use
            // try with resource to get the auto close magic.
            try (Txn<ByteBuffer> txn = env.txnRead()) {
                ByteBuffer key = makeKeyBuffer(id);
                final ByteBuffer found = dbi.get(txn, key);
                if (found != null) {
                    // copy to local Vert.x buffer from the LMDB mem managed array
                    Buffer buffer = Buffer.buffer(txn.val().remaining());
                    buffer.setBytes( 0 , txn.val() );
                    BsonObject doc = new BsonObject(buffer);
                    return doc;
                } else {
                    return null;
                }
            }
        }, stexec);
        return fut;
    }


    @Override
    public CompletableFuture<Void> put(String id, BsonObject doc) {
        CompletableFuture fut = CompletableFuture.runAsync( () -> {
            ByteBuffer key = makeKeyBuffer(id);
            byte[] valBytes = doc.encode().getBytes();
            final ByteBuffer val = allocateDirect(valBytes.length);
            val.put(valBytes).flip();
            dbi.put(key, val);
        }, stexec);
        return fut;
    }


    @Override
    public CompletableFuture<Boolean> delete(String id) {
        CompletableFuture fut = CompletableFuture.supplyAsync( () -> {
            ByteBuffer key = makeKeyBuffer(id);
            boolean deleted = dbi.delete(key);
            return deleted;
        }, stexec);
        return fut;
    }


    public CompletableFuture<Void> close() {
        CompletableFuture fut = CompletableFuture.runAsync( () -> {
            dbi.close();
            env.sync(true);
            stexec.shutdown();
        }, stexec);
        return fut;
    }


    private ByteBuffer makeKeyBuffer(String id) {
        final ByteBuffer key = allocateDirect(env.getMaxKeySize());
        key.put(id.getBytes(StandardCharsets.UTF_8)).flip();
        return key;
    }


}