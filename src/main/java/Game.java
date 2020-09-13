import org.apache.log4j.Logger;
import org.telegram.telegrambots.meta.api.objects.User;
import org.web3j.abi.datatypes.Int;
import org.web3j.contracts.eip20.generated.ERC20;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.core.methods.response.Web3ClientVersion;
import org.web3j.protocol.websocket.WebSocketClient;
import org.web3j.protocol.websocket.WebSocketService;
import org.web3j.tx.gas.ContractGasProvider;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class Game {

    final long chat_id;
    final int gameInitiator;
    final int minimumNumberOfPlayers;
    final Lucky_Gates_Bot lucky_gates_bot;
    Logger logger = Logger.getLogger(Game.class);
    Instant gameStartTime, gameCurrentTime, gameDestroyTime;
    boolean shouldTryToEstablishConnection = true;
    boolean prizeSent = false;
    boolean isSendingPrize = false;
    boolean shouldBreak = false;
    BigInteger prizePool;
    public int winnerId;
    String winnerAddy;
    String rewardTrx;
    private final boolean testMode;
    int notResponseCount = 0;

    final String CRTSContractAddress;
    final String ourWallet;
    final BigInteger TicketCost;
    private final ArrayList<String> webSocketUrls = new ArrayList<>();
    private WebSocketService webSocketService;


    // Constructor
    public Game(Lucky_Gates_Bot lucky_gates_bot, long chat_id, int playerInitiator, String CRTSContractAddress, String ourWallet, BigInteger TicketCost,
                int minimumNumberOfPlayers, boolean testMode) {
        webSocketUrls.add("wss://ws.tomochain.com");
        this.CRTSContractAddress = CRTSContractAddress;
        this.ourWallet = ourWallet;
        this.TicketCost = TicketCost;
        this.chat_id = chat_id;
        this.lucky_gates_bot = lucky_gates_bot;
        this.testMode = testMode;
        gameInitiator = playerInitiator;
        gameStartTime = Instant.now();
        this.minimumNumberOfPlayers = minimumNumberOfPlayers;
        gameDestroyTime = gameStartTime.plus(6, ChronoUnit.MINUTES);
        gameThread.start();
    }


    public boolean hasGameStarted;
    public boolean isAcceptingEntryPayment = false;
    private final ArrayList<Integer> player_Ids = new ArrayList<>();
    private final HashMap<Integer, User> players = new HashMap<>();
    private final ArrayList<Integer> didPlayerPay = new ArrayList<>();
    private HashMap<Integer, String> originalDoorChoice = new HashMap<>();
    private HashMap<Integer, Integer> currentPoints = new HashMap<>(); // replaced shotsToFire
    public int numberOfPlayers = 0;
    private int doorSelection = -1;


    Thread gameThread = new Thread() {
        @Override
        public void run() {
            super.run();

            gameCurrentTime = gameStartTime;
            boolean shouldClose = false;
            while (!hasGameStarted) {
                gameCurrentTime = Instant.now();

                if(numberOfPlayers >= 10) {
                    lucky_gates_bot.sendMessage(chat_id, "Maximum number of players have joined the game and we can proceed with payments");
                    hasGameStarted = true;
                }

                if(gameCurrentTime.compareTo(gameDestroyTime) > 0) {
                    if(numberOfPlayers >= minimumNumberOfPlayers) {
                        lucky_gates_bot.sendMessage(chat_id, "Join time over. Starting Game with " + numberOfPlayers + " players");
                        wait500ms();
                    } else {
                        lucky_gates_bot.sendMessage(chat_id, "Not enough players. Cancelling the game.");
                        hasGameStarted = true;
                        lucky_gates_bot.deleteGame(chat_id);
                        shouldClose = true;
                    }
                }
            }

            if (shouldClose) {
                try {
                    join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            lucky_gates_bot.sendMessage(chat_id, "Final Player list has been decided to\n\n" + getAllPlayerPing(), "https://media.giphy.com/media/j2pWZpr5RlpCodOB0d/giphy.gif");
            wait500ms();
            wait500ms();
            wait500ms();
            if(!testMode) {
                isAcceptingEntryPayment = true;
                lucky_gates_bot.sendMessage(chat_id, "All players must pay 1 ticket within ⏳ 2 minutes using the command /paywithticket");
                Instant paymentEndInstant = Instant.now().plus(2, ChronoUnit.MINUTES);
                while (Instant.now().compareTo(paymentEndInstant) < 0) {
                    if(didPlayerPay.size() == numberOfPlayers) {
                        break;
                    }
                    wait500ms();
                    wait500ms();
                    wait500ms();
                }

                for(int i = 0; i < numberOfPlayers; i++) {
                    if(!didPlayerPay.contains(player_Ids.get(i))) {
                        players.remove(player_Ids.get(i));
                        player_Ids.remove(i);
                        i--;
                        numberOfPlayers--;
                    }
                }

                if(numberOfPlayers < minimumNumberOfPlayers) {
                    lucky_gates_bot.sendMessage(chat_id, "Less than " + minimumNumberOfPlayers + " people paid the entry ticket. Cannot start the game. " +
                            "Closing the game." + "\n\nEveryone who paid via ticket will be refunded by adding 1 ticket" +
                            " to their account. You can use /mytickets command in private chat with me to check your number of tickets." + "It can " +
                            "take up to 5 minutes before you receive the refund. Please be patient");
                    for(int i = 0; i < numberOfPlayers; i++) {
                        lucky_gates_bot.refund1Ticket(player_Ids.get(i));
                    }
                    lucky_gates_bot.deleteGame(chat_id);
                    try {
                        join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                isAcceptingEntryPayment = false;
                lucky_gates_bot.sendMessage(chat_id, getAllPlayerPing() + "\n\nPayment for above players was confirmed. The game will start exactly after 30 seconds." +
                        "\n\nAll players will be kept notified until the game begins", "https://media.giphy.com/media/xOix1S8lhWAZa/giphy.gif");

                Instant minEnd = Instant.now().plus(30, ChronoUnit.SECONDS);
                Instant min5Warn = Instant.now().plus(15, ChronoUnit.SECONDS);
                boolean min5 = true;

                while (Instant.now().compareTo(minEnd) <= 0) {
                    if(min5) {
                        if(Instant.now().compareTo(min5Warn) > 0) {
                            lucky_gates_bot.sendMessage(chat_id, getAllPlayerPing() + "\n\n15 Seconds remaining before game start");
                            min5 = false;
                        }
                    }
                    wait500ms();
                    wait500ms();
                }
            } else {
                lucky_gates_bot.sendMessage(chat_id, "This is a test mode game and there will be no entry price or winner prize");
            }

            lucky_gates_bot.sendMessage(chat_id, getAllPlayerPing() + "\nGame has Begun...");
            wait500ms();
            wait500ms();

            prizePool = TicketCost.multiply(new BigInteger(Integer.toString(numberOfPlayers-1)));
            if(!testMode) {
                lucky_gates_bot.sendMessage(chat_id, "Total prize for the winner will be : " + prizePool.divide(new BigInteger("1000000000000000000")) + " CRTS." +
                        " This was calculated by the formula : TicketCost * (NumberOfPlayers - 1). Cost of each ticket is 10 crts");
            }


            ArrayList<String> nos = new ArrayList<>();
            nos.add("0");
            nos.add("0");
            nos.add("1");
            nos.add("1");
            nos.add("2");
            nos.add("3");
            currentPoints = new HashMap<>();
            for(int i = 0; i < numberOfPlayers; i++) {
                currentPoints.put(player_Ids.get(i), 0);
            }

            while (numberOfPlayers > 1) {
                originalDoorChoice = new HashMap<>();
                for(int i = 0; i < numberOfPlayers; i++) {
                    Collections.shuffle(nos);
                    StringBuilder str = new StringBuilder();
                    for (String no : nos) {
                        str.append(no);
                    }
                    originalDoorChoice.put(player_Ids.get(i), str.toString());
                }
                int currentTurnOfPlayer = 0;

                notResponseCount = 0;
                while (currentTurnOfPlayer < numberOfPlayers) {
                    startTurnOfPlayer(currentTurnOfPlayer);
                    currentTurnOfPlayer++;
                }
                if(notResponseCount == numberOfPlayers) {
                    lucky_gates_bot.sendMessage(chat_id, "No one responded in the current round. Dissolving the game.");
                    break;
                }
                lucky_gates_bot.sendMessage(chat_id, "Score Board : \n" + getAllPlayerPingWithPoints());
                int min = 100;
                int idx = 0;
                for(int i = 0; i < numberOfPlayers; i++) {
                    if(currentPoints.get(player_Ids.get(i)) < min) {
                        min = currentPoints.get(player_Ids.get(i));
                        idx = 1;
                    } else if(currentPoints.get(player_Ids.get(i)) == min) {
                        idx++;
                    }
                }
                if(idx == numberOfPlayers) {
                    lucky_gates_bot.sendMessage(chat_id, "All players have same number of points. Therefore all of you will play the next round.");
                    wait500ms();
                    wait500ms();
                } else if(numberOfPlayers != 1) {
                    String msg = "Least points are : " + min + "\nPlayers with " + min + " points will now be removed from the game";
                    for(int i = 0; i < numberOfPlayers; i++) {
                        if(currentPoints.get(player_Ids.get(i)) == min) {
                            msg += "\n\n@" + players.get(player_Ids.get(i)).getUserName() + " you have been removed from the game.";
                            players.remove(player_Ids.get(i));
                            player_Ids.remove(i);
                            i--;
                            numberOfPlayers--;
                        }
                    }
                    wait500ms();
                    wait500ms();
                    lucky_gates_bot.sendMessage(chat_id, msg);
                    wait500ms();
                    wait500ms();
                    wait500ms();
                    wait500ms();
                    wait500ms();
                    wait500ms();
                    wait500ms();
                    lucky_gates_bot.sendMessage(chat_id, getAllPlayerPing() + "\nYou all are the remaining player and shall play the next round.");
                    wait500ms();
                    wait500ms();
                    wait500ms();
                    wait500ms();
                }
            }

            if(numberOfPlayers == 1) {
                lucky_gates_bot.sendMessage(chat_id, "The winner of the game is : @" + players.get(player_Ids.get(0)).getUserName());
                if(!testMode) {
                    winnerId = player_Ids.get(0);
                    isSendingPrize = true;
                    lucky_gates_bot.sendMessage(chat_id, "You have won " + prizePool.divide(new BigInteger("1000000000000000000")) + " CRTS.");
                    wait500ms();
                    wait500ms();
                    wait500ms();
                    wait500ms();
                    lucky_gates_bot.sendMessage(chat_id, "@" + players.get(player_Ids.get(0)).getUserName() + " Please use  -->   /receive@Lucky_Gates_Bot " +
                            "TOMO_Wallet_address   <-- command (In the format shown without arrows and replacing the TOMO_Wallet_address with the tomo address of the wallet" +
                            " to which you want the price to be sent) WITHIN ⏳ 4 minutes. If you send an invalid address, you will lose your prize.");
                    Instant endIns = Instant.now().plus(4, ChronoUnit.MINUTES);
                    while (Instant.now().compareTo(endIns) < 0) {
                        if(getPrizeSentOrShouldBreak()) {
                            break;
                        }
                        wait500ms();
                        wait500ms();
                        wait500ms();
                        wait500ms();
                    }
                    if(prizeSent) {
                        lucky_gates_bot.sendMessage(chat_id, "The prize has been sent. Trx : " + rewardTrx);
                    } else {
                        lucky_gates_bot.sendMessage(chat_id, "4 minutes have passed. Either you have not provided your address or the provided address is invalid.");
                    }
                }
            }

            for(int i = 0; i < numberOfPlayers; i++) {
                players.remove(player_Ids.get(i));
                player_Ids.remove(i);
                i--;
                numberOfPlayers--;
            }
            lucky_gates_bot.sendMessage(chat_id, "Code by : @oregazembutoushiisuru");
            lucky_gates_bot.deleteGame(chat_id);
            try {
                join();
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    };

    Thread prizeSender = new Thread() {
        @Override
        public void run() {
            super.run();
            rewardTrx = sendRewardToWinner(prizePool, winnerAddy);
            if(rewardTrx != null) {
                prizeSent = true;
            }
            shouldBreak = true;
            try {
                join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };



    // By the end of this function, it would have been determined how many shots each player will fire
    private void startTurnOfPlayer(int playerIndex) {
        wait500ms();
        wait500ms();
        Instant endTime, halfTime, quarterTime;
        quarterTime = Instant.now();
        halfTime = Instant.now();
        endTime = Instant.now();
        int turnStage = 1;
        int halfValue = 0;
        int quarterValue = 0;
        boolean isWaiting = false;
        int doorWith0 = 0, doorWith1 = 0;
        boolean switched = false;

        OUTER:
        while (true) {
            if (!isWaiting) {
                switch (turnStage) {
                    case 1: {
                        halfValue = 45;
                        quarterValue = 20;
                        wait500ms();
                        wait500ms();
                        wait500ms();
                        wait500ms();
                        wait500ms();
                        lucky_gates_bot.sendMessage(chat_id, "Current turn of @" + players.get(player_Ids.get(playerIndex)).getUserName(),
                                "https://media.giphy.com/media/jQ8sHpRHbdqW1VsDvq/giphy.gif");
                        lucky_gates_bot.sendMessageWithButton(chat_id, "\uD83D\uDEAA You are standing in front of 6 doors \uD83D\uDEAA\n\n1 random " +
                                        "doors contain 3️⃣ points.\nAnother random door contains 2️⃣ points\n2 other doors contain 1️⃣ point each." +
                                        "\nRemaining 2 doors contains 0️⃣ points each.\n\nPick one door within ⏳ 90 seconds or else you will loose 1️⃣ point.",
                                new String[] {"\uD83D\uDEAA 1", "\uD83D\uDEAA 2", "\uD83D\uDEAA 3", "\uD83D\uDEAA 4", "\uD83D\uDEAA 5", "\uD83D\uDEAA 6"},
                                new String[] {"1", "2", "3", "4", "5", "6"});
                        wait500ms();
                        wait500ms();
                        endTime = Instant.now().plus(90, ChronoUnit.SECONDS);
                        halfTime = endTime.minus(45, ChronoUnit.SECONDS);
                        quarterTime = endTime.minus(20, ChronoUnit.SECONDS);
                        isWaiting  = true;
                        break;
                    }

                    case 2: {
                        boolean d1 = false, d0 = false;
                        for(int i = 0; i < 6; i++) {
                            if(!d1) {
                                if(i+1 != doorSelection) {
                                    if(originalDoorChoice.get(player_Ids.get(playerIndex)).charAt(i) == '1') {
                                        doorWith1 = i+1;
                                        d1 = true;
                                    }
                                }
                            }
                            if(!d0) {
                                if(i+1 != doorSelection) {
                                    if(originalDoorChoice.get(player_Ids.get(playerIndex)).charAt(i) == '0') {
                                        doorWith0 = i+1;
                                        d0 = true;
                                    }
                                }
                            }
                            if(d1 && d0) {
                                break;
                            }
                        }
                        StringBuilder doorPattern = new StringBuilder();
                        for(int i = 0; i < 6; i++) {
                            if(i == doorWith1-1) {
                                doorPattern.append("1️⃣");
                            } else if (i == doorWith0-1) {
                                doorPattern.append("0️⃣");
                            } else {
                                doorPattern.append("❎");
                            }
                            if(i != 5) {
                                doorPattern.append(" -- ");
                            }
                        }
                        halfValue = 30;
                        quarterValue = 15;
                        wait500ms();
                        wait500ms();
                        lucky_gates_bot.sendMessage(chat_id, "The gatekeeper will now open two doors", "https://media.giphy.com/media/J7fawBXeSAu3e/giphy.gif");
                        wait500ms();
                        wait500ms();
                        wait500ms();
                        wait500ms();
                        lucky_gates_bot.sendMessageWithButton(chat_id, "@" + players.get(player_Ids.get(playerIndex)).getUserName() + "\n\n" +
                                        doorPattern.toString() + "\n\n\uD83D\uDEAA Door " + doorWith1 + " has 1️⃣ point behind it." +
                                        "\n\uD83D\uDEAA Door " + doorWith0 + " has 0️⃣ points behind it.\n\nYou now have an option " +
                                        "to switch to one of the remaining three doors.\nYou have ⏳ 1 minute to switch. Would you like to switch?",
                                new String[] {"Switch", "Don't Switch"}, new String[] {"Switch", "Don't Switch"});
                        wait500ms();
                        endTime = Instant.now().plus(60, ChronoUnit.SECONDS);
                        halfTime = endTime.minus(30, ChronoUnit.SECONDS);
                        quarterTime = endTime.minus(15, ChronoUnit.SECONDS);
                        isWaiting  = true;
                        break;
                    }

                    case 3: {
                        String[] text = new String[3];
                        String[] data = new String[3];
                        int k = 0;
                        for(int i = 0; i < 6; i++) {
                            if(i != doorSelection-1 && i != doorWith1-1 && i != doorWith0-1) {
                                text[k] = "\uD83D\uDEAA " + (i + 1);
                                data[k] = Integer.toString((i+1));
                                k++;
                            }
                        }
                        halfValue = 30;
                        quarterValue = 15;
                        wait500ms();
                        wait500ms();
                        wait500ms();
                        wait500ms();
                        lucky_gates_bot.sendMessageWithButton(chat_id, "@" + players.get(player_Ids.get(playerIndex)).getUserName() + "\n\n" +
                                        "Please switch to one of the remaining doors. You have ⏳ 60 seconds to decide or else you loose 1️⃣ point",
                                text, data);
                        wait500ms();
                        wait500ms();
                        endTime = Instant.now().plus(60, ChronoUnit.SECONDS);
                        halfTime = endTime.minus(30, ChronoUnit.SECONDS);
                        quarterTime = endTime.minus(15, ChronoUnit.SECONDS);
                        isWaiting  = true;
                        break;
                    }

                    case 4: {
                        String sendVal;
                        int result = Integer.parseInt(String.valueOf(originalDoorChoice.get(player_Ids.get(playerIndex)).charAt(doorSelection-1)));
                        if(result == 0) {
                            sendVal = "0️⃣";
                        } else if(result == 1) {
                            sendVal = "1️⃣";
                        } else if(result == 2) {
                            sendVal = "2️⃣";
                        } else if(result == 3) {
                            sendVal = "3️⃣";
                        } else {
                            sendVal = "-1️⃣";
                        }
                        int points = currentPoints.get(player_Ids.get(playerIndex)) + result;
                        currentPoints.replace(player_Ids.get(playerIndex), points);
                        lucky_gates_bot.sendMessage(chat_id, "@" + players.get(player_Ids.get(playerIndex)).getUserName() + "\nOpening \uD83D\uDEAA Door No. "
                                + doorSelection,"https://media.giphy.com/media/xTiTnt0GGd5p3HpDBC/giphy.gif");
                        wait500ms();
                        wait500ms();
                        wait500ms();
                        lucky_gates_bot.sendMessage(chat_id, "It had " + sendVal + " points behind it.");
                        wait500ms();
                        wait500ms();
                        turnStage++;
                        break;
                    }

                    default: {
                        break OUTER;
                    }
                }
            }
            else {
                boolean halfWarn = true;
                boolean quarterWarn = true;
                boolean didNotAnswer = true;

                while (Instant.now().compareTo(endTime) < 0) {
                    if (lucky_gates_bot.didGetResponse()) {
                        
                        if(lucky_gates_bot.replier != player_Ids.get(playerIndex)) {
                            lucky_gates_bot.wrongReplier();
                            continue;
                        }
                        
                        didNotAnswer = false;
                        if (turnStage == 1) {
                            turnStage++;
                            doorSelection = Integer.parseInt(lucky_gates_bot.responseMsg);
                            lucky_gates_bot.setGotResponseFalse(chat_id,"You chose \uD83D\uDEAA Door : " + doorSelection);
                            isWaiting = false;
                            break;
                        } else if (turnStage == 2) {
                            switched = lucky_gates_bot.responseMsg.equals("Switch");
                            String msg;
                            if(switched) {
                                msg = "to Switch the \uD83D\uDEAA Doors";
                                turnStage++;
                            } else {
                                msg = "not to Switch the \uD83D\uDEAA Doors";
                                turnStage += 2;
                            }
                            lucky_gates_bot.setGotResponseFalse(chat_id,"You decided " + msg);
                            isWaiting = false;
                            break;
                        } else if (turnStage == 3) {
                            doorSelection = Integer.parseInt(lucky_gates_bot.responseMsg);
                            lucky_gates_bot.setGotResponseFalse(chat_id,"You chose \uD83D\uDEAA Door : " + doorSelection);
                            turnStage++;
                            isWaiting = false;
                            break;
                        }
                    
                    } else if(halfWarn) {
                        if (Instant.now().compareTo(halfTime) > 0) {
                            lucky_gates_bot.sendMessage(chat_id, "⏳ " + halfValue + " seconds remaining @" + players.get(player_Ids.get(playerIndex)).getUserName());
                            halfWarn = false;
                        }
                    } else if (quarterWarn) {
                        if (Instant.now().compareTo(quarterTime) > 0) {
                            lucky_gates_bot.sendMessage(chat_id, "⏳ " + quarterValue + " seconds remaining @" + players.get(player_Ids.get(playerIndex)).getUserName());
                            quarterWarn = false;
                        }
                    }
                }
                
                if (didNotAnswer) {
                    if(turnStage != 2) {
                        if(turnStage == 1) {
                            notResponseCount++;
                        }
                        lucky_gates_bot.sendMessage(chat_id, "@" + players.get(player_Ids.get(playerIndex)).getUserName() + " you didn't respond in time." +
                                " You have lost 1️⃣ point");
                        currentPoints.replace(player_Ids.get(playerIndex), currentPoints.get(player_Ids.get(playerIndex)) - 1);
                        turnStage = -1;
                        break;
                    } else {
                        lucky_gates_bot.sendMessage(chat_id, "You haven't chosen any option. Therefore you won't switch.");
                        turnStage += 2;
                        isWaiting = false;
                    }
                }
            }
        }
    }



    public void wait500ms() {
        try {
            Thread.sleep(500);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getAllPlayerPing() {
        StringBuilder retStr = new StringBuilder();
        for(int i = 0; i < numberOfPlayers; i++) {
            if(i != numberOfPlayers -1) {
                retStr.append("@").append(players.get(player_Ids.get(i)).getUserName()).append("\n");
                continue;
            }
            retStr.append("@").append(players.get(player_Ids.get(i)).getUserName());
        }
        return retStr.toString();
    }

    public String getAllPlayerPingWithPoints() {
        StringBuilder retStr = new StringBuilder();
        for(int i = 0; i < numberOfPlayers; i++) {
            retStr.append("@").append(players.get(player_Ids.get(i)).getUserName()).append(" : ").append(currentPoints.get(player_Ids.get(i)))
                    .append(" points").append("\n\n");
        }
        return retStr.toString();
    }

    public boolean addPlayer(User player) {
        if(players.containsKey(player.getId())) {
            return false;
        } else {
            players.put(player.getId(), player);
            player_Ids.add(player.getId());
            numberOfPlayers++;
            return true;
        }
    }

    public int getGameInitiator() {
        return gameInitiator;
    }

    public boolean beginGame() {
        if(numberOfPlayers >= minimumNumberOfPlayers) {
            hasGameStarted = true;
            return true;
        } else {
            return false;
        }
    }

    public boolean payWithTicketForThisUser(int playerId) {
        if(isAcceptingEntryPayment) {
            if(player_Ids.contains(playerId)) {
                if(didPlayerPay.contains(playerId)) {
                    lucky_gates_bot.sendMessage(chat_id, "@" + players.get(playerId).getUserName() + " Cannot pay with tickets. Your " +
                            "entry payment has already been confirmed.");
                    return false;
                } else {
                    didPlayerPay.add(playerId);
                    lucky_gates_bot.sendMessage(chat_id, "@" + players.get(playerId).getUserName() + " Pay with ticket successful");
                    return true;
                }
            } else {
                lucky_gates_bot.sendMessage(chat_id, "@" + players.get(playerId).getUserName() + " You are not part of the current game." +
                        " Cannot pay with tickets for anything.");
                return false;
            }
        } else {
            if(player_Ids.contains(playerId)) {
                lucky_gates_bot.sendMessage(chat_id, "@" + players.get(playerId).getUserName() + " The game is not accepting any entry payment " +
                        "at the moment");
            } else {
                lucky_gates_bot.sendMessage(chat_id, "You are not part of the game.");
            }
            return false;
        }
    }

    public void setShouldTryToEstablishConnection() {
        shouldTryToEstablishConnection = true;
    }

    public String sendRewardToWinner(BigInteger amount, String toAddress) {
        lucky_gates_bot.sendMessage(chat_id, "Process of sending the prize has been initiated");
        while (shouldTryToEstablishConnection) {
            try {
                Collections.shuffle(webSocketUrls);
                shouldTryToEstablishConnection = false;
                WebSocketClient webSocketClient = new WebSocketClient(new URI(webSocketUrls.get(0))) {
                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        super.onClose(code, reason, remote);
                        logger.info("WebSocket connection to " + uri + " closed successfully " + reason);
                    }

                    @Override
                    public void onError(Exception e) {
                        super.onError(e);
                        logger.error("WebSocket connection to " + uri + " failed with error");
                        e.printStackTrace();
                        System.out.println("Trying again");
                        setShouldTryToEstablishConnection();
                    }
                };
                webSocketService = new WebSocketService(webSocketClient, true);

                webSocketService.connect();
            } catch (Exception e) {
                e.printStackTrace();
            }
            wait500ms();
        }

        try {
            Web3j web3j = Web3j.build(webSocketService);
            Web3ClientVersion web3ClientVersion = web3j.web3ClientVersion().send();
            System.out.println("Game's Chat ID : " + chat_id + "\nWeb3ClientVersion : " + web3ClientVersion.getWeb3ClientVersion());
            TransactionReceipt trxReceipt = ERC20.load(CRTSContractAddress, web3j, Credentials.create(System.getenv("PrivateKey")), new ContractGasProvider() {
                @Override
                public BigInteger getGasPrice(String s) {
                    return BigInteger.valueOf(250000000L);
                }

                @Override
                public BigInteger getGasPrice() {
                    return BigInteger.valueOf(2500000000L);
                }

                @Override
                public BigInteger getGasLimit(String s) {
                    return BigInteger.valueOf(65000L);
                }

                @Override
                public BigInteger getGasLimit() {
                    return BigInteger.valueOf(65000L);
                }
            }).transfer(toAddress, amount).send();
            return trxReceipt.getTransactionHash();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public void startPrizeSend(String addy) {
        winnerAddy = addy;
        prizeSender.start();
        isSendingPrize = false;
    }

    public boolean getPrizeSentOrShouldBreak() {
        return (prizeSent || shouldBreak);
    }
}