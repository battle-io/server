/*
 * The GameManager class implements functions specific to the challenge.
 * Most game server development occurs here and in the game server class.
 */

package cw_generic;
import java.util.*;

public class GameManager {
    private int bot1ID;
    private int bot2ID;
    private boolean bot1Ready;
    private boolean bot2Ready;
    private int red;
    private int black;
    private int turn;
    private int victor;
    private String errorText;
    private int errorBID;
    private int errorCode;
    private int rated;
    private int gid;
    private static int currentgid=1;

     //NEW
    private int ROWS=6;
    private int COLS=7;
    private int moves[];
    private int map[][];
    private int height[];

    private Vector gd;  //Store game data as a vector

    //Constructor
    public GameManager(int bot1ID, int bot2ID){
        this.bot1ID = bot1ID;
        this.bot1Ready = false;
        this.bot2ID = bot2ID;
        this.bot2Ready = false;
        this.map = loadMap();
        this.gid = currentgid;
        currentgid++;
        init();
    }

    // Initialize a game.
    public void init(){
        this.errorBID = 0;
        this.errorCode = 0;
        this.errorText = "";
        this.gd = new Vector();
        this.height=new int[COLS];      //Store piece "height" in each slot.
        this.moves=new int[ROWS*COLS];  //Represent board as an array 0-41 positions.
        for(int i=0;i<this.moves.length;i++){
            this.moves[i]=0;  //Zero represents an open position, 1=red, 2=black
        }
        for(int i=0;i<this.height.length;i++){
            this.height[i]=0; //Reset height of each slot to 0;
        }

        // If bot2ID==0, then this is a "protocol test" and the game should not
        // be rated.
        if(this.bot2ID==0){
            red=bot1ID;
            black=bot2ID;
            this.rated = 0;
        }else{
            this.rated = 1;
            // Select a starting player at random.
            Random generator = new Random();
            if(generator.nextInt(2)>=1){
                red=bot1ID;
                black=bot2ID;
            }else{
                red=bot2ID;
                black=bot1ID;
            }
        }
        turn=red;
    }

    public int getGID(){
        return gid;
    }

    public int getBID1(){
        return bot1ID;
    }
    public int getBID2(){
        return bot2ID;
    }
    public int getErrorBID(){
        return errorBID;
    }
    public String getErrorText(){
        return this.errorText;
    }

    //Both bots must echo the "GAME_INITIALIZE" command before before proceeding.
    public boolean botsReady(){
        if(this.bot1Ready & (this.bot2Ready | this.bot2ID==0)){
            return true;
        }else{
            return false;
        }
    }

    public void setReady(int bid){
        if(bot1ID==bid){
            this.bot1Ready=true;
        }else{
            this.bot2Ready=true;
        }
    }

    //Retrieves last move from the game data array.
    public String getLastMove(){
        if(gd.size()>0)
            return (String)gd.lastElement().toString();
        return "-1";
    }

    public int getTurn(){
        return turn;
    }

    public int getOpp(){
        if (bot1ID==turn)
            return bot2ID;
        return bot1ID;
    }

    public int getMoveCount(){
        return gd.size();
    }

    //Returns gameData as a comma delimited string (which can be forwarded to
    //bots or the switch for logging.
    public String getGameData(){
        String out="";
        if(gd.size()>1){
            for(Enumeration e = gd.elements(); e.hasMoreElements();){
                out+=(String)e.nextElement().toString();
                if(e.hasMoreElements()){
                    out+=",";
                }
            }
        }else{
            out="No Moves Recorded";
        }
        return out;
    }

    // ConnectFour performs all game data logging on the switch.  This string
    // is used to call a stored procedure which lives on the switch.  This allows
    // the central site to control all pertinant bot/game information if desired.
    // It is not necessary to implement these functions.  Implementing these
    // functions requires close communication with a code-wars admin to create
    // a challenge database and writing stored procedures.
    public String logGame(){
        return "insert_gamedata:"+victor+","+red+","+black+",'"+getGameData()+"',"+errorCode+","+errorBID+",'"+errorText+"',"+rated;
    }

    // Validation Match is frequently refered to as a "protocol test."  A connecting
    // bot is asked to make several moves validating that they can send an receive
    // commands before playing a real match.
    public boolean isValidationMatch(){
        if (bot2ID==0)
            return true;
        return false;
    }

    public boolean isLiveMatch(){
        if (bot2ID<0)
            return true;
        return false;
    }

    // Make a random move.  Used for the validation/protocol test.
    public void makeValidMove(){
        Random generator = new Random();
        int move = generator.nextInt(6)+1;
        while(!makeMove(move)){
            move = generator.nextInt(6);
        }
        nextPlayer();
    }
    
    // In the future, input conditioning should be performed in a single place.  
    // For now, its spread throughout several functions.
    public boolean processMove(String in){
        if(isNumeric(in)){
            return makeMove(Integer.parseInt(in));
        }else{
            this.errorText = in + " is not a valid play";
            this.errorBID = turn;
            return false;
        }
    }

    // If a move is valid, record it in the moves * height arrays.
    public boolean makeMove(int move){
        //is the move valid (1-7)
        if(move < 1 | move > 7){
            this.errorText = move + " is not a valid play";
            this.errorBID = turn;
            return false;
        }
        //is the move legal (column not full)
        int col = move-1; //First column is 0;
        if (height[col]+1 > ROWS){
            this.errorText = "Column "+move+" is already full";
            this.errorBID = turn;
            return false;
        }else{
            moves[COLS * height[col]+col] = turn;
            height[col]++;
            gd.add(move);
            return true;
        }
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

    public boolean hasWon(){
        //Only check for win conditions after 7 moves
        boolean gameOver=false;
        if(gd.size()>6){
            //Checks the whole board for wins
            int cursor=0;
            int count=0;

            for(int i=0;i<map.length;i++){
                count=0;
                for(int j=0;j<4;j++){
                    cursor = map[i][j];
                    if(moves[cursor]==turn){
                        count++;
                    }
                }
                if(count==4){
                    gameOver=true;                    
                }
            }
            if(gameOver){
                victor=turn;
                return true;
            }
        }
        nextPlayer();
        return false;
    }

    public boolean isDraw(){
        for(int i=0; i<COLS;i++){
            if(height[i]<ROWS){
                return false;
            }
        }
        victor=0;
        return true;
    }

    private void nextPlayer(){
        if(turn==bot1ID){
            turn=bot2ID;
        }else{
            turn=bot1ID;
        }
    }

    // Returns the metadata for a GAME_INITIALIZE command. If no parameter is
    // provided, only sent gameId. (Its a validation match/protocol test).
    public String formGameInitializeCmd(){
        String cmdTxt=String.valueOf(gid);
        return cmdTxt;
    }

    // Make a more exciting GAME_INITIALIZE command string.  Definitely more
    // exciting.
    public String formGameInitializeCmd(int bid){
        String color="error";
        if(bid==red){
            color="red";
        }else{
            color="black";
        }
        if(this.bot1ID==bid){
            return color+":"+String.valueOf(this.bot2ID);
        }else if(this.bot2ID==bid){
            return color+":"+String.valueOf(this.bot1ID);
        }
        return "Error";
    }


    public String formGameReportCmd(int bid){
        String cmdTxt="";
        if(bid==this.bot1ID){
            cmdTxt = bot1ID+":"+red + ":" + black + ":" + victor + ":" + getGameData();
        }else{
            cmdTxt = bot2ID+":"+red + ":" + black + ":" + victor + ":" + getGameData();
        }
        return cmdTxt;
    }


    // Matrix of all possible connectFour plays.
    private int[][] loadMap(){
       int[][] out = new int[][] {
            //Horizontal
            { 0, 1, 2, 3},
            { 1, 2, 3, 4},
            { 2, 3, 4, 5},
            { 3, 4, 5, 6},
            { 7, 8, 9,10},
            { 8, 9,10,11},
            { 9,10,11,12},
            {10,11,12,13},
            {14,15,16,17},
            {15,16,17,18},
            {16,17,18,19},
            {17,18,19,20},
            {21,22,23,24},
            {22,23,24,25},
            {23,24,25,26},
            {24,25,26,27},
            {28,29,30,31},
            {29,30,31,32},
            {30,31,32,33},
            {31,32,33,34},
            {35,36,37,38},
            {36,37,38,39},
            {37,38,39,40},
            {38,39,40,41},
            //Vertical
            { 0, 7,14,21},
            { 1, 8,15,22},
            { 2, 9,16,23},
            { 3,10,17,24},
            { 4,11,18,25},
            { 5,12,19,26},
            { 6,13,20,27},
            { 7,14,21,28},
            { 8,15,22,29},
            { 9,16,23,30},
            {10,17,24,31},
            {11,18,25,32},
            {12,19,26,33},
            {13,20,27,34},
            {14,21,28,35},
            {15,22,29,36},
            {16,23,30,37},
            {17,24,31,38},
            {18,25,32,39},
            {19,26,33,40},
            {20,27,34,41},
            //Diagonal Right \
            {21,15, 9, 3},
            {22,16,10, 4},
            {23,17,11, 5},
            {24,18,12, 6},
            {28,22,16,10},
            {29,23,17,11},
            {30,24,18,12},
            {31,25,19,13},
            {35,29,23,17},
            {36,30,24,18},
            {37,31,25,19},
            {38,32,26,20},
            //Diagonal Left  /
            {24,16, 8, 0},
            {25,17, 9, 1},
            {26,18,10, 2},
            {27,19,11, 3},
            {31,23,15, 7},
            {32,24,16, 8},
            {33,25,17, 9},
            {34,26,18,10},
            {38,30,22,14},
            {39,31,23,15},
            {40,32,24,16},
            {41,33,25,17}
            };
        return out;
    }



}
