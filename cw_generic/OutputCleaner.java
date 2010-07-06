/*
 * The Output Cleaner thread injects a CLEAN_OUTPUT command which removes any
 * expired commands destined for the web interface via Thrift.  Rate is set by
 * the purgeInterval parameter.
 */
package cw_generic;

import java.util.concurrent.*;
import org.apache.log4j.Logger;

/**
 * Fires events for automated challenge scheduling
 *
 * @author KingOfSpades
 */
public class OutputCleaner extends Thread {
    private Logger logger;
    public BlockingQueue<Command> commandQueue = new LinkedBlockingQueue();
    private long purgeInterval;

    public OutputCleaner(BlockingQueue<Command> commandQueue, Logger logger, long purgeInterval) {
        this.logger = logger;
        this.commandQueue = commandQueue;
        this.purgeInterval = purgeInterval;
    }

    @Override
    public void run() {
        logger.info("OutputCleaner Started");
        int x=0;
        try {
            this.setName("OutputCleaner");
            Command cmd=new Command("CLEAN_OUTPUT", "null");
            while(true){
                Thread.sleep(purgeInterval);
                commandQueue.add(cmd);
            }
        } catch (InterruptedException e) {
            logger.fatal("ChallengeInterval Interrupted");
            return;
        }
    }
}

