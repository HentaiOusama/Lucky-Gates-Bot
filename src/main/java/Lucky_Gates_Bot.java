import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class Lucky_Gates_Bot extends org.telegram.telegrambots.bots.TelegramLongPollingBot {

    private final String ourWallet, CRTSContractAddress;
    private final BigInteger joinCost;
    private int minimumNumberOfPlayers;

    public HashMap<Long, Game> currentlyActiveGames = new HashMap<>();
    boolean gotResponse = false;
    String responseMsg = "";
    String callbackQueryId = "";
    int responseMsgId = 0;
    int replier = 0;
    final String mongoDBUri;
    final String databaseName = "Lucky-Gates-Bot-Database";
    final String botControlDatabaseName = "All-Bots-Command-Centre";
    final String botName = "Lucky Gates Bot";
    final String idKey = "UserID", ticketKey = "Tickets";
    final ArrayList<Integer> playersBuyingTickets = new ArrayList<>();
    final HashMap<Long, TicketBuyer> ticketBuyers = new HashMap<>();
    final HashMap<Long, ArrayList<Integer>> messagesForDeletion = new HashMap<>();
    MongoClient mongoClient;
    MongoDatabase mongoDatabase, botControlDatabase;
    MongoCollection userDataCollection, botControlCollection;
    boolean shouldRunGame;
    boolean testMode = false;
    long awakeChatId = -1001477389485L;
    Document botNameDoc, foundBotNameDoc;

    // Constructor
    public Lucky_Gates_Bot(String ourWallet, String CRTSContractAddress, BigInteger joinCost, int minimumNumberOfPlayers) {
        this.ourWallet = ourWallet;
        this.CRTSContractAddress = CRTSContractAddress;
        this.joinCost = joinCost;
        this.minimumNumberOfPlayers = minimumNumberOfPlayers;
        mongoDBUri = "mongodb+srv://" + System.getenv("LuckyGatesMonoID") + ":" +
                System.getenv("LuckyGatesMonoPass") + "@hellgatesbotcluster.zm0r5.mongodb.net/test";
        MongoClientURI mongoClientURI = new MongoClientURI(mongoDBUri);
        mongoClient = new MongoClient(mongoClientURI);
        mongoDatabase = mongoClient.getDatabase(databaseName);
        botControlDatabase = mongoClient.getDatabase(botControlDatabaseName);
        userDataCollection = mongoDatabase.getCollection("UserTickets");
        botControlCollection = botControlDatabase.getCollection("MemberValues");
        botNameDoc = new Document("botName", botName);
        foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
        shouldRunGame = (boolean) foundBotNameDoc.get("shouldRunGame");
    }



    @Override
    public void onUpdateReceived(Update update) {
        if(update.hasMessage()) {
            long chat_id = update.getMessage().getChatId();
            if(chat_id == getAdminChatId()) {
                if(update.getMessage().hasText()) {
                    String text = update.getMessage().getText();
                    if(!shouldRunGame && text.equalsIgnoreCase("run")) {
                        shouldRunGame = true;
                        botNameDoc = new Document("botName", botName);
                        foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                        Bson updatedAddyDoc = new Document("shouldRunGame", shouldRunGame);
                        Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                        botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                    } else if(text.startsWith("MinPlayers = ")) {
                        try {
                            minimumNumberOfPlayers = Integer.parseInt(text.trim().split(" ")[2]);
                            sendMessage(chat_id, "Min players set to " + minimumNumberOfPlayers);
                        } catch (Exception e) {
                            sendMessage(chat_id, "Invalid number of players");
                        }
                    }
                    else if(text.equalsIgnoreCase("TestMode")) {
                        testMode = true;
                    } else if (text.equalsIgnoreCase("ExitTestMode")) {
                        testMode = false;
                    }
                    else if(text.equalsIgnoreCase("Stop")) {
                        shouldRunGame = false;
                        botNameDoc = new Document("botName", botName);
                        foundBotNameDoc = (Document) botControlCollection.find(botNameDoc).first();
                        Bson updatedAddyDoc = new Document("shouldRunGame", shouldRunGame);
                        Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                        botControlCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                    } else if(text.equalsIgnoreCase("Commands")) {
                        sendMessage(chat_id, "Run\nMinPlayers = __\nTestMode\nExitTestMode\nStop\nCommands");
                    }
                    sendMessage(update.getMessage().getChatId(), "shouldRunGame = " + shouldRunGame + "\nTestMode = " + testMode +
                            "\nMinPlayers = " + minimumNumberOfPlayers);
                }
                // Can add special operation for admin here
            }
        }
        if(!shouldRunGame) {
            return;
        }

        if(update.hasCallbackQuery()) {
            update.getUpdateId();
            callbackQueryId = update.getCallbackQuery().getId();
            responseMsgId = update.getCallbackQuery().getMessage().getMessageId();
            responseMsg = update.getCallbackQuery().getData();
            replier = update.getCallbackQuery().getFrom().getId();
            gotResponse = true;
        } else if(update.hasMessage()) {
            int fromId = update.getMessage().getFrom().getId();
            long chat_id = update.getMessage().getChatId();
            String[] inputMsg = update.getMessage().getText().trim().split(" ");
            switch (inputMsg[0]) {

                case "/startgame":
                case "/startgame@Lucky_Gates_Bot": {
                    SendMessage sendMessage = new SendMessage();
                    boolean shouldSend = true;
                    if (update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat()) {
                        if(chat_id != -1001487755827L && chat_id != -1001391125843L && chat_id != awakeChatId) { // chat_id != -1001487755827L
                            sendMessage(chat_id, "This bot is only built to be used in CRTS GAME-SWAP CHANNEL");
                            return;
                        }
                        if (currentlyActiveGames.containsKey(chat_id)) {
                            sendMessage.setText("A game is already running. Please wait for current game to end to start a new one or you can join the current game");
                        } else {
                            Document document = new Document(idKey, fromId);
                            Document foundAddyDoc = (Document) userDataCollection.find(document).first();
                            if(foundAddyDoc != null) {
                                try {
                                    int tickets = (int) foundAddyDoc.get(ticketKey);
                                    if(tickets > 0) {
                                        Game newGame;
                                        sendMessage(chat_id, "Initiating a new Game!!!");
                                        newGame = new Game(this, chat_id, fromId, CRTSContractAddress, ourWallet, joinCost, minimumNumberOfPlayers,
                                                testMode);
                                        newGame.addPlayer(update.getMessage().getFrom());
                                        currentlyActiveGames.put(chat_id, newGame);
                                        messagesForDeletion.put(chat_id, new ArrayList<>());
                                        SendAnimation sendAnimation = new SendAnimation();
                                        sendAnimation.setAnimation("https://media.giphy.com/media/xThuW1VhsD5J6cJD4k/giphy.gif");
                                        sendAnimation.setCaption("New game has been created. Please gather at least " + minimumNumberOfPlayers + " players (up to " +
                                                "6 players maximum) within 6 minutes for game to begin. Players can use /join command to join the current game.");
                                        sendAnimation.setChatId(chat_id);
                                        execute(sendAnimation);
                                        shouldSend = false;
                                    } else {
                                        sendMessage.setText("You have 0 tickets. Cannot start or join a game");
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    shouldSend = false;
                                }
                            } else {
                                sendMessage.setText("You have 0 tickets. Cannot start or join a game");
                                document.append(ticketKey, 0);
                                userDataCollection.insertOne(document);
                            }
                        }
                    } else {
                        sendMessage.setText("This command can only be run in a group!!!");
                    }
                    sendMessage.setChatId(chat_id);
                    if(shouldSend) {
                        try {
                            execute(sendMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    break;
                }

                case "/join":
                case "/join@Lucky_Gates_Bot": {
                    SendMessage sendMessage = new SendMessage();
                    if (update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat()) {
                        Document document = new Document(idKey, fromId);
                        Document foundAddyDoc = (Document) userDataCollection.find(document).first();
                        if(foundAddyDoc != null) {
                            int tickets = (int) foundAddyDoc.get(ticketKey);
                            if(tickets > 0) {
                                if (currentlyActiveGames.containsKey(chat_id)) {
                                    Game game = currentlyActiveGames.get(chat_id);
                                    if(game.hasGameStarted){
                                    sendMessage.setText("Current game has already begun. Please wait for next game");
                                } else {
                                        if (game.addPlayer(update.getMessage().getFrom())) {
                                        sendMessage.setText("You have successfully join the game @" + update.getMessage().getFrom().getUserName());
                                    } else {
                                        sendMessage.setText("You are already in the game @" + update.getMessage().getFrom().getUserName());
                                    }
                                    }
                                } else {
                                sendMessage.setText("No Games Active. Start a new one to join");
                                }
                            } else {
                                sendMessage.setText("You have 0 tickets. Cannot start or join a game");
                            }
                        } else {
                            sendMessage.setText("You have 0 tickets. Cannot start or join a game");
                            document.append(ticketKey, 0);
                            userDataCollection.insertOne(document);
                        }
                    } else {
                        sendMessage.setText("This command can only be run in a group!!!");
                    }
                    sendMessage.setChatId(chat_id);
                    try {
                        execute(sendMessage);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }
                    break;
                }

                case "/begin":
                case "/begin@Lucky_Gates_Bot": {
                    boolean shouldSend = true;
                    SendMessage sendMessage = new SendMessage();
                    if (update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat()) {
                        if (currentlyActiveGames.containsKey(chat_id)) {
                            Game game = currentlyActiveGames.get(chat_id);
                            if(game.hasGameStarted){
                                sendMessage.setText("Cannot begin. The current has already begun!");
                            } else {
                                if (game.getGameInitiator() == fromId) {
                                    if(game.beginGame()) {
                                        shouldSend = false;
                                    } else {
                                        sendMessage.setText("Cannot begin the game. Not Enough Players!\nCurrent Number of Players : " + game.numberOfPlayers);
                                    }
                                } else {
                                    try {
                                        SendAnimation sendAnimation = new SendAnimation();
                                        sendAnimation.setAnimation("https://media.giphy.com/media/Lr9Y5rWFIpcsTSodLj/giphy.gif");
                                        sendAnimation.setCaption("/begin command can only be used by the person who initiated the game or the game automatically " +
                                                "start after the join time ends and minimum number of players are found");
                                        sendAnimation.setChatId(chat_id);
                                        execute(sendAnimation);
                                        shouldSend = false;
                                    } catch (Exception e) {
                                        sendMessage.setText("/begin command can only be used by the person who initiated the game or the game automatically " +
                                                "start after the join time ends and minimum number of players are found");
                                    }
                                }
                            }
                        } else {
                            sendMessage.setText("No Games Active. Although you CAN use /Start command to start a new game XD");
                        }
                    } else {
                        sendMessage.setText("This command can only be run in a group!!!");
                    }
                    sendMessage.setChatId(chat_id);
                    if (shouldSend) {
                        try {
                            execute(sendMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    break;
                }

                case "/mytickets":
                case "/mytickets@Lucky_Gates_Bot": {
                    SendMessage sendMessage = new SendMessage();
                    if(!update.getMessage().getChat().isUserChat()) {
                        sendMessage.setText("Please use /mytickets command in private chat");
                    } else {
                        Document document = new Document(idKey, fromId);
                        Document foundAddyDoc = (Document) userDataCollection.find(document).first();
                        if(foundAddyDoc != null) {
                            int tickets = (int) foundAddyDoc.get(ticketKey);
                            sendMessage.setText("You currently have " + tickets + " tickets");
                        } else {
                            document.append(ticketKey, 0);
                            userDataCollection.insertOne(document);
                            sendMessage.setText("You have 0 tickets.");
                        }
                    }
                    sendMessage.setChatId(chat_id);
                    try {
                        execute(sendMessage);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }
                    break;
                }

                case "/paywithticket":
                case "/paywithticket@Lucky_Gates_Bot": {
                    boolean shouldSend = true;
                    SendMessage sendMessage = new SendMessage();
                    if (update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat()) {
                        if (currentlyActiveGames.containsKey(chat_id)) {
                            Document document = new Document(idKey, fromId);
                            Document foundAddyDoc = (Document) userDataCollection.find(document).first();
                            if(foundAddyDoc != null) {
                                int tickets = (int) foundAddyDoc.get(ticketKey);
                                if(tickets > 0) {
                                    Game game = currentlyActiveGames.get(chat_id);
                                    if(game.payWithTicketForThisUser(fromId)){
                                        tickets--;
                                        Bson updatedAddyDoc = new Document(ticketKey, tickets);
                                        Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                                        userDataCollection.updateOne(foundAddyDoc, updateAddyDocOperation);
                                        shouldSend = false;
                                    }
                                } else {
                                    sendMessage.setText("@" + update.getMessage().getFrom().getUserName() + " You have 0 tickets. Cannot " +
                                            "pay with tickets.");
                                }
                            } else {
                                sendMessage.setText("You have 0 tickets. Cannot pay with tickets");
                                document.append(ticketKey, 0);
                                userDataCollection.insertOne(document);
                            }
                        } else {
                            sendMessage.setText("@" + update.getMessage().getFrom().getUserName() + " No active game. Not accepting any " +
                                    "entry payment at the moment");
                        }
                    } else {
                        sendMessage.setText("This command can only be run in a group!!!");
                    }
                    sendMessage.setChatId(chat_id);
                    if (shouldSend) {
                        try {
                            execute(sendMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    break;
                }

                case "/buytickets":
                case "/buytickets@Lucky_Gates_Bot": {
                    SendMessage sendMessage = new SendMessage();
                    boolean shouldSend = true;
                    if(!update.getMessage().getChat().isUserChat()) {
                        sendMessage.setText("Please use /buytickets command in private chat @" + getBotUsername());
                    } else {
                        if (inputMsg.length != 3) {
                            sendMessage.setText("Proper format to use this command is (Everything space separated) :\n\n/buytickets your_Tomo_Addy amount_To_Buy");
                        } else {
                            if (playersBuyingTickets.contains(fromId)) {
                                sendMessage.setText("Please complete your current purchase before starting a new purchase");
                            } else {
                                Document searchDoc = new Document(idKey, fromId);
                                Document foundDoc = (Document) userDataCollection.find(searchDoc).first();
                                if (foundDoc != null) {
                                    int amountToBuy;
                                    try {
                                        amountToBuy = Integer.parseInt(inputMsg[2]);
                                        TicketBuyer ticketBuyer = new TicketBuyer(this, chat_id, amountToBuy,
                                                inputMsg[1], ourWallet, CRTSContractAddress, joinCost);
                                        playersBuyingTickets.add(fromId);
                                        ticketBuyers.put(chat_id, ticketBuyer);
                                        shouldSend = false;
                                    } catch (Exception e) {
                                        sendMessage.setText("Proper format to use this command is (Everything space separated) :\n\n/buytickets " +
                                                "your_Tomo_Addy amount_To_Buy\n\n\nWhere amount has to be a number");
                                    }
                                } else {
                                    try {
                                        searchDoc.append(ticketKey, 0);
                                        userDataCollection.insertOne(searchDoc);
                                        int amountToBuy;
                                        try {
                                            amountToBuy = Integer.parseInt(inputMsg[2]);
                                            TicketBuyer ticketBuyer = new TicketBuyer(this, chat_id, amountToBuy,
                                                        inputMsg[1], ourWallet, CRTSContractAddress, joinCost);
                                            playersBuyingTickets.add(fromId);
                                            ticketBuyers.put(chat_id, ticketBuyer);
                                            shouldSend = false;
                                        } catch (Exception e) {
                                            sendMessage.setText("Proper format to use this command is (Everything space separated) :\n\n/buytickets " +
                                                    "your_Tomo_Addy amount_To_Buy\n\nWhere amount has to be a number");
                                        }
                                    } catch (Exception e) {
                                        sendMessage.setText("Invalid Format");
                                    }
                                }
                            }
                        }
                    }
                    sendMessage.setChatId(chat_id);
                    if(shouldSend) {
                        try {
                            execute(sendMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    break;
                }

                case "/receive":
                case "/receive@Lucky_Gates_Bot": {
                    SendMessage sendMessage = new SendMessage();
                    boolean shouldSend = true;
                    if (update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat()) {
                        if (currentlyActiveGames.containsKey(chat_id)) {
                            Game game = currentlyActiveGames.get(chat_id);
                            if(game.isSendingPrize) {
                                if(fromId == game.winnerId) {
                                    if(inputMsg.length == 2) {
                                        game.startPrizeSend(inputMsg[1]);
                                        shouldSend = false;
                                    } else {
                                        sendMessage.setText("Invalid Format. Proper Format :-\n/receive@Lucky_Gates_Bot TOMO_Address");
                                    }
                                } else {
                                    sendMessage.setText("You are not the winner. You cannot use this command");
                                }
                            } else {
                                sendMessage.setText("Cannot use this command at the moment. Use this command to receive prize after winning a game");
                            }
                        } else {
                            sendMessage.setText("Cannot use this command at the moment. Use this command to receive prize after winning a game");
                        }
                    } else {
                        sendMessage.setText("This command can only be run in a group!!!");
                    }
                    sendMessage.setChatId(chat_id);
                    if(shouldSend) {
                        try {
                            execute(sendMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    break;
                }

                case "/transfertickets":
                case "/transfertickets@Lucky_Gates_Bot": {
                    SendMessage sendMessage = new SendMessage();
                    if(update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat()) {
                        if(update.getMessage().isReply()) {
                            if(inputMsg.length != 2) {
                                sendMessage.setText("Proper format to use this command is : /transfertickets@Lucky_Gates_Bot amountToTransfer");
                            } else {
                                try {
                                    int amountToTransfer = Integer.parseInt(inputMsg[1]);
                                    int FromId = fromId;
                                    int ToId = update.getMessage().getReplyToMessage().getFrom().getId();
                                    Document FromDocument = new Document(idKey, FromId);
                                    Document ToDocument = new Document(idKey, ToId);
                                    Document foundFromDoc = (Document) userDataCollection.find(FromDocument).first();
                                    Document foundToDoc = (Document) userDataCollection.find(ToDocument).first();
                                    if(foundFromDoc != null) {
                                        try {
                                            int tickets = (int) foundFromDoc.get(ticketKey);
                                            if(tickets >= amountToTransfer) {
                                                Bson updatedAddyDoc = new Document(ticketKey, tickets - amountToTransfer);
                                                Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                                                userDataCollection.updateOne(foundFromDoc, updateAddyDocOperation);
                                                if(foundToDoc != null) {
                                                    tickets = (int) foundToDoc.get(ticketKey);
                                                    updatedAddyDoc = new Document(ticketKey, tickets + amountToTransfer);
                                                    updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                                                    userDataCollection.updateOne(foundToDoc, updateAddyDocOperation);
                                                } else {
                                                    ToDocument.append(ticketKey, amountToTransfer);
                                                    userDataCollection.insertOne(ToDocument);
                                                }
                                                sendMessage.setText("Ticket Transfer Successful");
                                            } else {
                                                sendMessage.setText("You don't have enough tickets");
                                            }
                                        } catch (Exception e) {
                                            sendMessage.setText("Invalid. Bot Error");
                                        }
                                    } else {
                                        try {
                                            FromDocument.append(ticketKey, 0);
                                            userDataCollection.insertOne(FromDocument);
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                        sendMessage.setText("You don't have enough tickets");
                                    }
                                } catch (Exception e) {
                                    sendMessage.setText("Proper format to use this command is : /transfertickets@Lucky_Gates_Bot amountToTransfer");
                                    sendMessage.setText("Amount has to be a number");
                                }
                            }
                        } else {
                            sendMessage.setText("This message has to be a reply type message quoting any message of the person to whom you want to " +
                                    "transfer the tickets");
                        }
                    } else {
                        sendMessage.setText("This command can only be run in a group!!!");
                    }
                    sendMessage.setChatId(chat_id);
                    try {
                        execute(sendMessage);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    break;
                }

                case "/addtickets":
                case "/addtickets@Lucky_Gates_Bot": {
                    if(fromId != getAdminChatId()) {
                        sendMessage(chat_id, "You are not allowed to use this command");
                        return;
                    } else {
                        if(update.getMessage().isReply()) {
                            int Id = update.getMessage().getReplyToMessage().getFrom().getId();
                            Document document = new Document(idKey, Id);
                            Document foundDoc = (Document) userDataCollection.find(document).first();
                            int ticks = Integer.parseInt(update.getMessage().getText().trim().split(" ")[1]);
                            if(foundDoc != null) {
                                try {
                                    int tickets = (int) foundDoc.get(ticketKey) + ticks;
                                    Bson updatedAddyDoc = new Document(ticketKey, tickets);
                                    Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                                    userDataCollection.updateOne(foundDoc, updateAddyDocOperation);
                                } catch (Exception e) {
                                    sendMessage(chat_id, "Invalid Format");
                                }
                            } else {
                                try {
                                    document.append(ticketKey, ticks);
                                    userDataCollection.insertOne(document);
                                } catch (Exception e) {
                                    sendMessage(chat_id, "Invalid Format");
                                }
                            }
                            sendMessage(chat_id, "Successfully Added " + ticks + " Ticket");
                        } else {
                            sendMessage(chat_id, "This message has to be a reply type message");
                        }
                    }
                    break;
                }

                default: {
                    String msg = update.getMessage().getText().trim();
                    if(msg.endsWith("@Lucky_Gates_Bot") && msg.startsWith("/")) {
                        SendMessage sendMessage = new SendMessage();
                        sendMessage.setText("No such command exists");
                        sendMessage.setChatId(chat_id);
                        try {
                            execute(sendMessage);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    @Override
    public String getBotUsername() {
        return "Lucky_Gates_Bot";
    }

    @Override
    public String getBotToken() {
        return (System.getenv("LuckyGatesBotTokenA") + ":" + System.getenv("LuckyGatesBotTokenB"));
    }


    public void sendMessage(long chat_id, String msg, String... url) {
        if(url.length == 0) {
            SendMessage sendMessage = new SendMessage();
            sendMessage.setText(msg);
            sendMessage.setChatId(chat_id);
            try {
                int messageId = execute(sendMessage).getMessageId();
                if(messagesForDeletion.containsKey(chat_id)) {
                    messagesForDeletion.get(chat_id).add(messageId);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            ArrayList<Integer> index = new ArrayList<>();
            for(int i = 0; i < url.length; i++) {
                index.add(i);
            }
            Collections.shuffle(index);
            SendAnimation sendAnimation = new SendAnimation();
            sendAnimation.setAnimation(url[index.get(0)]);
            sendAnimation.setCaption(msg);
            sendAnimation.setChatId(chat_id);
            try {
                int messageId = execute(sendAnimation).getMessageId();
                if(messagesForDeletion.containsKey(chat_id)) {
                    messagesForDeletion.get(chat_id).add(messageId);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void sendMessageWithButton(long chat_id, String msg, String[] buttonText, String[] buttonValues) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setText(msg);
        sendMessage.setChatId(chat_id);
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine = new ArrayList<>();
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
        for(int i = 0; i < buttonText.length; i++) {
            rowInLine.add(new InlineKeyboardButton().setText(buttonText[i]).setCallbackData(buttonValues[i]));
        }
        rowsInLine.add(rowInLine);
        inlineKeyboardMarkup.setKeyboard(rowsInLine);
        sendMessage.setReplyMarkup(inlineKeyboardMarkup);
        try {
            int messageId = execute(sendMessage).getMessageId();
            messagesForDeletion.get(chat_id).add(messageId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendEditMessage(long chat_id, String msg, int msg_Id) {
        EditMessageText editMessageText = new EditMessageText().setMessageId(msg_Id);
        editMessageText.setChatId(chat_id);
        editMessageText.setText(msg);
        AnswerCallbackQuery answerCallbackQuery = new AnswerCallbackQuery();
        answerCallbackQuery.setCallbackQueryId(callbackQueryId);
        callbackQueryId = "";
        try {
            execute(editMessageText);
            execute(answerCallbackQuery);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

    }

    public void setGotResponseFalse(long chat_id, String msg) {
        sendEditMessage(chat_id, msg, responseMsgId);
        gotResponse = false;
        responseMsgId = 0;
        responseMsg = "";
    }

    public boolean didGetResponse() {
        return gotResponse;
    }

    public void wrongReplier() {
        AnswerCallbackQuery answerCallbackQuery = new AnswerCallbackQuery();
        answerCallbackQuery.setCallbackQueryId(callbackQueryId);
        answerCallbackQuery.setText("You are not allowed to pick during someone else's turn.");
        callbackQueryId = "";
        gotResponse = false;
        responseMsgId = 0;
        responseMsg = "";
        try {
            execute(answerCallbackQuery);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void deleteGame(long chat_id) {
        deleteMessages(chat_id);
        currentlyActiveGames.remove(chat_id);
    }

    public long getAdminChatId() {
        return 607901021;
    }

    public void refund1Ticket(int playerId) {
        Document fetchDocument = new Document(idKey, playerId);
        Document gotDocument = (Document) userDataCollection.find(fetchDocument).first();
        if(gotDocument != null) {
            int tickets = (int) gotDocument.get(ticketKey);
            tickets++;
            Bson replaceDoc = new Document(ticketKey, tickets);
            Bson replaceDocOOperation = new Document("$set", replaceDoc);
            userDataCollection.updateOne(fetchDocument, replaceDocOOperation);
        }
    }

    public void playerTicketPurchaseEnded(int playerId, int numberOfTicketsToBuy, boolean didPay) {
        if(didPay) {
            Document document = new Document(idKey, playerId);
            Document foundDocument = (Document) userDataCollection.find(document).first();
            if(foundDocument != null) {
                int tickets = (int) foundDocument.get(ticketKey);
                Bson updateDoc = new Document(ticketKey, (numberOfTicketsToBuy + tickets));
                Bson updateDocOperation = new Document("$set", updateDoc);
                userDataCollection.updateOne(foundDocument, updateDocOperation);
            }
        }
        playersBuyingTickets.remove((Integer) playerId);
        ticketBuyers.remove((long) playerId);
    }

    private void deleteMessages(long chat_id) {
        ArrayList<Integer> deletion = messagesForDeletion.get(chat_id);
        while (deletion.size() > 5) {
            DeleteMessage deleteMessage = new DeleteMessage();
            deleteMessage.setChatId(chat_id);
            deleteMessage.setMessageId(deletion.get(0));
            try {
                execute(deleteMessage);
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
            deletion.remove(0);
        }
    }
}