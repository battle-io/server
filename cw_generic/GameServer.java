/*
 * The GameServer class is responsible for coordinating responses to incoming
 * commands from the switch and the various game server threads.  The
 * commandQueue data structure stores all incomming commands for processing.
 * There is also a sendQueue for messages to be sent to the switch as well as
 * to individual bots (via the switch).  Commands destined for the browser
 * (via the Thrift interface) are placed in the outQueue construct.  GameServer
 * is also responsible for maintaining a list of all connected bots and ongoing
 * games.  These lists grow and shrink in response to bot logins and
 * disconnections.
 * All incoming commands are processed in order by the CommandProcessor, a
 * subclass to GameServer.  This function parses a command by type and calls
 * the appropriate routine.  It is important to note that the CommandProcessor
 * is a single threaded object.  Only one command can be acted upon
 * simultaneously.  This reduces the risk of concurrency issues.
 * GameServer subroutines are fairly self documenting.  Login functions add new
 * bot instances to the bots array.  Disconnections must be robustly handled
 * to prevent alienating data.  With each CHALLENGE command, new matches are
 * initiated for all idle bots.
 * As mentioned elsewhere, Thrift is used to communicate directly with the web
 * browser.  All thrift functions are indicated in the function comments.
 * Thrift calls are not guaranteed to be thread safe so extra care is
 * required to reduce the risk of concurrency issues.  Additional information
 * on Thrift can be found here: http://incubator.apache.org/thrift/.
 */
package cw_generic;

import java.util.*;
import java.util.concurrent.*;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TBinaryProtocol.Factory;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TTransportException;
import connectFourServer.gen.WebInterface;

public class GameServer {

    public final BlockingQueue<Command> commandQueue = new LinkedBlockingQueue();                                    // List of all commands to be processed by the commandProcessor()
    public final BlockingQueue<CmdPack> sendQueue = new LinkedBlockingQueue();                                    // List of commands to be sent to the switch and/or bots
    public final List<OutputPack> outQueue = Collections.synchronizedList(new ArrayList<OutputPack>());    // List of all messages destined for the web (via Thrift)
    public final List<Integer> activeQueue = Collections.synchronizedList(new ArrayList<Integer>());       // List of all active authors challenging their bot.
    private static final Logger logger = Logger.getLogger(GameServer.class);
    static private List<BotManager> bots = Collections.synchronizedList(new ArrayList<BotManager>());    // Actively maintained to include all online bots.
    static private List<GameManager> games = Collections.synchronizedList(new ArrayList<GameManager>());   // All currently active games.
    private long challengeInterval = 60000;  // Sets the frequency of CHALLENGE events in miliseconds
    private long outputPurgeInterval = 5000;  // Sets the expiration time for web-output commands in miliseconds

    public static void main(String args[]) throws Exception {
        PropertyConfigurator.configure(args[0]);
        GameServer gs = new GameServer(args);
    }

    public GameServer(String[] args) throws Exception {
        initialize(args);
        //Launch all supporting threads:
        //Contact, Authenticate & Listen to the switch.
        new Thread(new SwitchListener(this.commandQueue, this.sendQueue, this.outQueue, logger), "SwitchListener").start();
        new Thread(new ThriftServer(), "ThriftServer").start();
        new Thread(new CommandProcessor(), "CommandProcessor").start();
        new Thread(new OutputCleaner(this.commandQueue, logger, this.outputPurgeInterval), "OutputCleaner").start();
        new Thread(new ChallengeTimer(this.commandQueue, logger, this.challengeInterval), "ChallengeTimer").start();
    }

    // Set several parameters defined at the command line.
    private void initialize(String[] args) {
        try {
            this.challengeInterval = Long.parseLong(args[1]);
        } catch (Exception e) {
            System.out.println("Problem loading ... something " + e);
            e.printStackTrace(System.out);
        }
    }

    //Code Based on tutorial @ http://skorage.org/2009/03/08/simple-thrift-tutorial/
    public class ThriftServer extends Thread {

        @Override
        public void run() {
            try {
                TServerSocket serverTransport = new TServerSocket(7911);
                WebInterface.Processor processor = new WebInterface.Processor(new WebInterfaceImpl());
                Factory protFactory = new TBinaryProtocol.Factory(true, true);
                TServer server = new TThreadPoolServer(processor, serverTransport, protFactory);
                logger.info("Starting server on port 7911 ...");
                server.serve();
            } catch (TTransportException e) {
                e.printStackTrace();
            }
        }
    }

    //Code Based on tutorial @ http://skorage.org/2009/03/08/simple-thrift-tutorial/
    //All commands originate from the web interface.
    //All commands except "fetch_response" are one directional (no response required).
    //Fetch response looks through a queue of outgoing commands maintained by
    //the game server and returns with any which belong to the caller.
    //Sychronization is required because Thrift is not guaranteed to be thread
    //safe.
    class WebInterfaceImpl implements connectFourServer.gen.WebInterface.Iface {

        @Override
        public void setMode(int bid, int mode) throws TException {
            Command cmd = new Command("SET_MODE", bid + ":null:" + mode);
            commandQueue.add(cmd);
            logger.info("setMode() fired!");
        }

        @Override
        public void startGame(int bid) throws TException {
            Command cmd = new Command("WEB_START_GAME", Integer.toString(bid));
            commandQueue.add(cmd);
            logger.info("startGame() fired!");
        }

        @Override
        public void makeMove(int bid, int move) throws TException {
            Command cmd = new Command("ACTION_REPLY", (-bid) + ":null:" + move);
            commandQueue.add(cmd);
            logger.info("makeMove() " + move + " fired!");
        }

        @Override
        public void abortGame(int bid) throws TException {
            Command cmd = new Command("DISCONNECT_BOT_REMOTE", Integer.toString(-bid));
            commandQueue.add(cmd);
            logger.info("abortGame() fired!");
        }

        @Override
        public String fetchResponse(int bid) throws TException {
            activeQueue.add(-bid); //Human is Active!
            String response = "";
            List<OutputPack> remQueue = Collections.synchronizedList(new ArrayList<OutputPack>());
            //Lock on outQueue to prevent simultaneous access/modification.
            synchronized (outQueue) {
                for (OutputPack p : outQueue) {
                    if (p.getBID() == -bid) {
                        //Multiple commands can be separated by a "&&" this ONLY
                        //Applies for gameserver to web communication!
                        response = response.concat(p.getCmd() + "&&");
                        remQueue.add(p);
                    }
                }
                outQueue.removeAll(remQueue);
            }
            logger.info("fetchResponse() fired!");
            return response;
        }
    }

    /* Remove all expired commands in the outQueue construct.  outQueue can be
     * modified by multiple threads, so synchronization is required for data
     * consistency.  Disconnect any bot/human  which fails to "pick up its mail"
     */
    private void cleanOutput() {
        synchronized (outQueue) {
            List<BotManager> toRemove = Collections.synchronizedList(new ArrayList<BotManager>());
            long currentTime = System.currentTimeMillis();
            for (OutputPack p : outQueue) {
                if (p.getEXP() <= currentTime) {
                    BotManager human;
                    if ((human = botByBID(p.getBID())) != null) {
                        if (!toRemove.contains(human)) {
                            toRemove.add(human);
                        }
                    }
                }
            }
            for (BotManager b : toRemove) {
                disconnect(b);
            }
        }
    }

    /* Launch a game initiated from the web-interface via thrift.
     * Connected Human players are stored in the activeQueue and assigned an ID
     * equal to -bid.  Each human can only challenge their own bot online.
     */
    private void webStartGame(Command cmd) {
        int bid = Integer.parseInt(cmd.getMetaData());
        int hid = -bid; //HumanID
        BotManager human;
        if ((human = botByBID(hid)) != null) {
            disconnect(human);
        }
        synchronized (outQueue) {
            BotManager bot;
            if ((bot = botByBID(bid)) != null) {
                if (!bot.isBusy()) {
                    human = new BotManager(hid, 1, "human");
                    bots.add(human);
                    GameManager g = new GameManager(bot.getBID(), human.getBID());
                    games.add(g);
                    bot.setBusy(true);
                    bot.setGID(g.getGID());
                    human.setBusy(true);
                    human.setGID(g.getGID());
                    g.setReady(human.getBID());
                    sendQueue.add(new CmdPack(new Command("GAME_INITIALIZE", g.formGameInitializeCmd(bot.getBID())), bot.getBID(), 5000));
                    sendQueue.add(new CmdPack(new Command("GAME_INITIALIZE", g.formGameInitializeCmd(human.getBID())), human.getBID(), 5000));
                    logger.info("Human : " + hid + " logged in.");
                } else {
                    sendQueue.add(new CmdPack(new Command("SERVER_MESSAGE", "Bot " + bot.getBID() + " is Busy!"), hid));
                }
            } else {
                sendQueue.add(new CmdPack(new Command("SERVER_MESSAGE", "Bot " + bid + " is not Online!"), hid));
            }
        }
    }

    // Login a connecting bot.
    private void login(Command cmd) {
        //Protect duplicate logins even if this should be prevented by the switch.
        String[] metaData = cmd.getMetaData().split(":");
        if (!metaData.equals(null) && metaData.length >= 1) {
            int bid = Integer.parseInt(metaData[0]); //The first parameter for any bot driven command should be bid.  It is inserted by the switch during the command forwarding routine.
            int mode = Integer.parseInt(getParamValue("mode", metaData[1]));
            String language = "null"; //Currently useless.
            BotManager b = new BotManager(bid, mode, language);
            bots.add(b);
            sendQueue.add(new CmdPack(new Command("SERVER_MESSAGE", "GameServer Confirmed Connection"), bid, 0));
            GameManager g = new GameManager(b.getBID(), 0);
            games.add(g);
            b.setGID(g.getGID());
            b.setBusy(true);
            sendQueue.add(new CmdPack(new Command("GAME_INITIALIZE", g.formGameInitializeCmd()), b.getBID(), 5000));
            logger.info("Bot : " + bid + " logged in.");
        }
    }

    // Triggered upon receipt of a GAME_INITIALIZE command which has been echoed
    // by a bot.
    private void setReadyStatus(Command cmd) {
        String[] metaData = cmd.getMetaData().split(":");
        if (null != metaData && metaData.length >= 1) {
            int bid = Integer.parseInt(metaData[0]); //The first parameter for any bot driven command should be bid.  It is inserted by the switch during the command forwarding routine.
            BotManager b;
            if ((b = botByBID(bid)) != null) {
                int gid = b.getGID();
                GameManager g;
                if ((g = gameByGID(gid)) != null) {
                    g.setReady(bid);
                    if (g.botsReady()) {
                        //Send Action Requests
                        sendQueue.add(new CmdPack(new Command("ACTION_REQUEST", g.getLastMove()), g.getTurn(), 5000));
                    }
                } else {
                    //CMD: Bot is not involved in a game - message discarded
                    logger.error(bid + " is not involved in a game - message discarded.");
                }
            } else {
                //Bot does not exist in GS botlist.  Getting here should be impossible.
                logger.error(bid + " does not exist in GS botlist");
            }
        } else {
            //CMD: Error in metadata format.  Please consult the Connect4 protocol.
            logger.error("error in metaData formate");
        }
    }

    // Usually triggered after a completed/aborted game.  Sets bot status to idle
    // in preparation for another game.
    private void setIdle(int bid) {
        BotManager b;
        if ((b = botByBID(bid)) != null) {
            b.setBusy(false);
        }
    }

    /* Process a move made by a bot.  Handle protocol test moves differently from
     * standard moves.  If the game has ended (successfully) log it and send
     * game reports to both bots.
     */
    private void actionReply(Command cmd) {
        String[] metaData = cmd.getMetaData().split(":");
        BotManager b;
        if ((b = botByBID(cmd.getBID())) != null) {
            if (metaData.length == 3) {
                int gid = b.getGID();   // Find bot's current game.
                GameManager g;
                if ((g = gameByGID(gid)) != null) {
                    if (g.processMove(metaData[2])) {
                        if (!(g.isValidationMatch() & g.getMoveCount() > 5)) {
                            if (!(g.isDraw() | g.hasWon())) {
                                if (g.isValidationMatch()) {
                                    g.makeValidMove();
                                }
                                sendQueue.add(new CmdPack(new Command("ACTION_REQUEST", g.getLastMove()), g.getTurn(), 5000));
                            } else {
                                String test = g.logGame(); // apparently I was having difficulty with this in the past.(?)
                                sendQueue.add(new CmdPack(new Command("EXECUTE_PROCEDURE", test)));
                                sendQueue.add(new CmdPack(new Command("GAME_REPORT", g.formGameReportCmd(g.getBID1())), g.getBID1()));
                                sendQueue.add(new CmdPack(new Command("GAME_REPORT", g.formGameReportCmd(g.getBID2())), g.getBID2()));
                                setIdle(g.getBID1());
                                setIdle(g.getBID2());
                                games.remove(g);
                            }
                        } else {
                            //Validation Match/Protocol test has concluded
                            sendQueue.add(new CmdPack(new Command("SERVER_MESSAGE", "Protocol Test Passed!"), g.getBID1()));
                            setIdle(g.getBID1());
                            games.remove(g);
                        }
                    } else {
                        //Bot has made an invalid move
                        disconnectionByGameServer(b, "Bot has made an invalid move.");
                    }
                } else {
                    //CMD: Bot is not involved in a game - message discarded
                    logger.fatal("Bot " + b.getBID() + " is not involved in a game.");
                    disconnectionByGameServer(b, "ACTION_REPLY sent out of sequence.");
                }
            } else {
                logger.fatal("Incorrect number of arguments for ACTION_REPLY command.");
                disconnectionByGameServer(b, "Incorrect number of arguments for ACTION_REPLY command.");
            }
        } else {
            logger.fatal("No Bot associated with received ACTION_REPLY command.");
        }
    }

    /*
     * Periodically schedule games between any connected bots.
     */
    private void challengeEvent() {
        //Disconnect Inactive Humans
        List<BotManager> toRemove = new ArrayList<BotManager>();
        for (BotManager b : bots) {
            if (b.getBID() < 0 & !activeQueue.contains(b.getBID())) {
                toRemove.add(b);
            }
        }
        activeQueue.clear();
        for (BotManager b : toRemove) {
            disconnect(b);
        }

        List<BotManager> list = new ArrayList<BotManager>();
        String status;
        logger.info("==================");
        for (BotManager b : bots) {
            //For now, just show bot status'
            if (b.getBID() > 0) {
                if (b.isBusy()) {
                    status = "busy!";
                } else {
                    status = "idle!";
                    list.add(b);
                }
                logger.info(b.getBID() + " " + status);
            }
        }
        if (list.size() >= 2) {
            Collections.shuffle(list);
            while (list.size() >= 2) {
                GameManager g = new GameManager(list.get(0).getBID(), list.get(1).getBID());
                list.get(0).setGID(g.getGID());
                list.get(0).setBusy(true);
                list.get(1).setGID(g.getGID());
                list.get(1).setBusy(true);
                games.add(g);
                sendQueue.add(new CmdPack(new Command("GAME_INITIALIZE", g.formGameInitializeCmd()), list.get(0).getBID(), 5000));
                sendQueue.add(new CmdPack(new Command("GAME_INITIALIZE", g.formGameInitializeCmd()), list.get(1).getBID(), 5000));
                list.remove(0);
                list.remove(0);
                logger.info("Game #" + g.getGID() + " has begun!");
            }
        }
    }

    private void switchMessage(Command cmd) {
        logger.warn(cmd.getMetaData());
    }

    private void setMode(Command cmd) {
        String[] metaData = cmd.getMetaData().split(":");
        if (!metaData.equals(null) && metaData.length == 3) {
            int bid = Integer.parseInt(metaData[0]);
            int mode = Integer.parseInt(metaData[2]);
            String modeStr;
            BotManager b;
            if ((b = botByBID(bid)) != null) {
                b.setMode(mode);
                if (b.getMode() == 1) {
                    modeStr = "live";
                } else {
                    modeStr = "debug";
                }
                sendQueue.add(new CmdPack(new Command("SERVER_MESSAGE", "Bot has entered " + modeStr + " mode!"), bid, 0));
            }
        }
    }

    // =======UTILITIES=======
    private void disconnectionByGameServer(BotManager b, String reason) {
        int gid = b.getGID();
        GameManager g;
        if ((g = gameByGID(gid)) != null) {
            sendQueue.add(new CmdPack(new Command("DISCONNECT_BOT_REMOTE", reason), b.getBID()));
        }
        disconnect(b);
    }

    private void disconnectionBySwitch(Command cmd) {
        BotManager b;
        if ((b = botByBID(cmd.getBID())) != null) {
            disconnect(b);
        } else {
            logger.warn("Invalid Disconnect command recieved from switch");
        }
    }

    //All disconnections coming from the switch
    private void disconnect(BotManager b) {
        if (b.isBusy()) {
            GameManager g;
            if ((g = gameByGID(b.getGID())) != null) {
                if (g.getBID1() > 0 || b.getBID() != g.getBID1()) {
                    sendQueue.add(new CmdPack(new Command("GAME_ABORT", g.getGameData()), g.getBID1()));
                }
                if (g.getBID2() > 0 || b.getBID() != g.getBID2()) {
                    sendQueue.add(new CmdPack(new Command("GAME_ABORT", g.getGameData()), g.getBID2()));
                }
                sendQueue.add(new CmdPack(new Command("SERVER_MESSAGE", "Opponent disconnected or made an invalid move!"), g.getOpp()));
                setIdle(g.getOpp());
                games.remove(g);
            }
        }
        cleanOutQueue(b);
        bots.remove(b);
        logger.info("Bot " + b.getBID() + " has been removed from list.");
    }

    //Remove any extraneous messages from a disconnecting bot.
    private synchronized void cleanOutQueue(BotManager b) {
        if (b.getBID() < 0) {
            List<OutputPack> remQueue = Collections.synchronizedList(new ArrayList<OutputPack>());
            synchronized (outQueue) {
                for (OutputPack p : outQueue) {
                    if (p.getBID() == b.getBID()) {
                        remQueue.add(p);
                    }
                }
                outQueue.removeAll(remQueue);
            }
        }
    }

    private String getParamValue(String name, String params) {
        String val = "";
        String[] parts;
        String[] pair;
        parts = params.split(",");
        for (int i = 0; i < parts.length; i++) {
            pair = parts[i].split("=");
            if (pair[0].equals(name)) {
                return pair[1].toString();
            }
        }
        return null;
    }

    private BotManager botByBID(int bid) {
        for (BotManager b : bots) {
            if (b.getBID() == bid) {
                return b;
            }
        }
        return null;
    }

    private BotManager botByGID(int gid) {
        for (BotManager b : bots) {
            if (b.getGID() == gid) {
                return b;
            }
        }
        return null;
    }

    private GameManager gameByGID(int gid) {
        for (GameManager g : games) {
            if (g.getGID() == gid) {
                return g;
            }
        }
        return null;
    }

    private boolean isNumeric(String str) {
        String validChars = "0123456789";
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (validChars.indexOf(c) == -1) {
                return false;
            }
        }
        return true;
    }

    public class CommandProcessor extends Thread {

        private Command cmd;

        @Override
        public void run() {
            while (true) {
                try {
                    cmd = commandQueue.take();
                    //System.out.println("FROM SW => " +cmd.getCommandType()+"<<"+cmd.getMetaData());
                    if (cmd.getCommandType().equals("LOGIN_INFORM")) {
                        login(cmd);
                    } else if (cmd.getCommandType().equals("GAME_INITIALIZE")) {
                        setReadyStatus(cmd);
                    } else if (cmd.getCommandType().equals("ACTION_REPLY")) {
                        actionReply(cmd);
                    } else if (cmd.getCommandType().equals("DISCONNECT_BOT_REMOTE")) {
                        disconnectionBySwitch(cmd);
                    } else if (cmd.getCommandType().equals("CHALLENGE")) {
                        challengeEvent();
                    } else if (cmd.getCommandType().equals("SERVER_MESSAGE")) {
                        switchMessage(cmd);
                    } else if (cmd.getCommandType().equals("WEB_START_GAME")) {
                        webStartGame(cmd);
                    } else if (cmd.getCommandType().equals("SET_MODE")) {
                        setMode(cmd);
                    } else if (cmd.getCommandType().equals("CLEAN_OUTPUT")) {
                        cleanOutput();
                    } else {
                        logger.warn("Unrecognized Command Forwarded From Server: " + cmd.getCommandType());
                    }

                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (NullPointerException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}