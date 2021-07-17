import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class Lucky_Gates_Bot extends org.telegram.telegrambots.bots.TelegramLongPollingBot {

    private final String ourWallet, ANONContractAddress;
    private final BigInteger joinCost;
    private int minimumNumberOfPlayers;
    private final HashMap<String, Game> currentlyActiveGames = new HashMap<>();
    final HashMap<Long, String> playersBuyingTickets = new HashMap<>();
    final HashMap<String, TicketBuyer> ticketBuyers = new HashMap<>();
    final HashMap<String, ArrayList<Integer>> messagesForDeletion = new HashMap<>();
    boolean shouldRunGame, waitingToSwitchServers = false;

    // Callback Query
    boolean gotResponse = false;
    String responseMsg = "";
    String callbackQueryId = "";
    int responseMsgId = 0;
    long replier = 0;

    // MongoDB Vars
    final MongoClient mongoClient;
    final String databaseName = "Lucky-Gates-Bot-Database";
    final String botName = "Lucky Gates Bot";
    final String idKey = "UserID", ticketKey = "Tickets";
    MongoCollection<Document> userDataCollection;
    Document botNameDoc, foundBotNameDoc;

    // Constructor
    public Lucky_Gates_Bot(String ourWallet, String ANONContractAddress, BigInteger joinCost, int minimumNumberOfPlayers) {
        this.ourWallet = ourWallet;
        this.ANONContractAddress = ANONContractAddress;
        this.joinCost = joinCost;
        this.minimumNumberOfPlayers = minimumNumberOfPlayers;

        ConnectionString connectionString = new ConnectionString(
                "mongodb+srv://" + System.getenv("mongoID") + ":" +
                        System.getenv("mongoPass") + "@hellgatesbotcluster.zm0r5.mongodb.net/test" +
                        "?keepAlive=true&poolSize=30&autoReconnect=true&socketTimeoutMS=360000&connectTimeoutMS=360000"
        );
        MongoClientSettings mongoClientSettings = MongoClientSettings.builder()
                .applyConnectionString(connectionString).retryWrites(true).writeConcern(WriteConcern.MAJORITY).build();
        mongoClient = MongoClients.create(mongoClientSettings);
        mongoClient.startSession();
        userDataCollection = mongoClient.getDatabase(databaseName).getCollection("UserTickets");
        botNameDoc = new Document("botName", botName);
        foundBotNameDoc = userDataCollection.find(botNameDoc).first();
        assert foundBotNameDoc != null;
        shouldRunGame = (boolean) foundBotNameDoc.get("shouldRunGame");
    }


    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            if (update.getMessage().hasText()) {
                String chatId = update.getMessage().getChatId().toString();
                String text = update.getMessage().getText();
                if (chatId.equalsIgnoreCase(getAdminChatId())) {
                    if (!shouldRunGame && text.equalsIgnoreCase("runBot")) {
                        shouldRunGame = true;
                        botNameDoc = new Document("botName", botName);
                        foundBotNameDoc = userDataCollection.find(botNameDoc).first();
                        Bson updatedAddyDoc = new Document("shouldRunGame", shouldRunGame);
                        Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                        userDataCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                    } else if (text.startsWith("MinPlayers = ")) {
                        try {
                            minimumNumberOfPlayers = Integer.parseInt(text.trim().split(" ")[2]);
                            sendMessage(chatId, "Min players set to " + minimumNumberOfPlayers);
                        } catch (Exception e) {
                            sendMessage(chatId, "Invalid number of players");
                        }
                    } else if (text.equalsIgnoreCase("stopBot")) {
                        shouldRunGame = false;
                        botNameDoc = new Document("botName", botName);
                        foundBotNameDoc = userDataCollection.find(botNameDoc).first();
                        Bson updatedAddyDoc = new Document("shouldRunGame", shouldRunGame);
                        Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                        userDataCollection.updateOne(foundBotNameDoc, updateAddyDocOperation);
                    } else if (text.equalsIgnoreCase("StartServerSwitchProcess")) {
                        waitingToSwitchServers = true;
                        sendMessage(chatId, "From now, the bot won't accept new games or Ticket buy requests. Please use \"ActiveProcesses\" command " +
                                "to see how many games are active and then switch when there are no games active games and ticket buyers.");
                    } else if (text.equalsIgnoreCase("ActiveProcesses")) {
                        sendMessage(chatId, "Active Games : " + currentlyActiveGames.size() + "\nPeople buying tickets : " + playersBuyingTickets.size());
                    } else if (text.equalsIgnoreCase("Commands")) {
                        sendMessage(chatId, """
                                runBot
                                stopBot
                                MinPlayers = __
                                StartServerSwitchProcess
                                ActiveProcesses
                                Commands""");
                    }
                    sendMessage(chatId, "shouldRunGame = " + shouldRunGame + "\nMinPlayers = " + minimumNumberOfPlayers +
                            "\nWaitingToSwitchServers = " + waitingToSwitchServers);
                    // Can add special operation for admin here
                }
            }

            if (!shouldRunGame) {
                sendMessage(update.getMessage().getChatId().toString(), "Bot under maintenance. Please try again later.");
                return;
            }
        }

        if (update.hasCallbackQuery()) {
            update.getUpdateId();
            callbackQueryId = update.getCallbackQuery().getId();
            responseMsgId = update.getCallbackQuery().getMessage().getMessageId();
            responseMsg = update.getCallbackQuery().getData();
            replier = update.getCallbackQuery().getFrom().getId();
            gotResponse = true;
        } else if (update.hasMessage()) {
            long fromId = update.getMessage().getFrom().getId();
            String userName = update.getMessage().getFrom().getUserName();
            String chatId = update.getMessage().getChatId().toString();
            String[] inputMsg = update.getMessage().getText().trim().split("[ ]+");
            if (waitingToSwitchServers) {
                if (!inputMsg[0].startsWith("/receive") && !inputMsg[0].startsWith("/paywithticket")) {
                    sendMessage(chatId, "The bot is not accepting any commands at the moment. The bot will be changing the servers soon. So a buffer time has been " +
                            "provided to complete all active games and Ticket purchases. This won't take much long. Please expect a 15-30 minute delay. This process has to be" +
                            "done after every 15 days.");
                    return;
                }
            }
            switch (inputMsg[0]) {
                case "/startgame", "/startgame@Lucky_Gates_Bot" -> {
                    if (update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat()) {
                        if (!(chatId.equalsIgnoreCase("-1001477389485") &&
                                chatId.equalsIgnoreCase("-1001529888769"))) {
                            sendMessage(chatId, "This bot is only built to be used in ANON INU GROUP");
                            return;
                        }

                        if (currentlyActiveGames.containsKey(chatId)) {
                            Game game = currentlyActiveGames.get(chatId);
                            if (!game.hasGameStarted) {
                                sendMessage(chatId, "A game is already running. Please wait for current game to end to start a new one or you can join the current game");
                            } else {
                                sendMessage(chatId, "A game is already open. Use /join to join the current game");
                            }
                        } else {
                            Document document = new Document(idKey, fromId);
                            Document foundAddyDoc = userDataCollection.find(document).first();
                            if (foundAddyDoc != null) {
                                try {
                                    int tickets = (int) foundAddyDoc.get(ticketKey);
                                    if (tickets > 0) {
                                        Game newGame;
                                        sendMessage(chatId, "Initiating a new Game!!!");
                                        newGame = new Game(this, chatId, fromId, ANONContractAddress, ourWallet, joinCost, minimumNumberOfPlayers);
                                        newGame.addPlayer(update.getMessage().getFrom());
                                        currentlyActiveGames.put(chatId, newGame);
                                        messagesForDeletion.put(chatId, new ArrayList<>());
                                        sendMessage(chatId, "New game has been created. Please gather at least " + minimumNumberOfPlayers + " players (up to " +
                                                        "6 players maximum) within 6 minutes for game to begin. Players can use /join command to join the current game.",
                                                "https://media.giphy.com/media/xThuW1VhsD5J6cJD4k/giphy.gif");
                                    } else {
                                        sendMessage(chatId, "You have 0 tickets. Cannot start or join a game. Use /buytickets (in private chat " +
                                                "@" + getBotUsername() + ") to buy tickets");
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            } else {
                                sendMessage(chatId, "You have 0 tickets. Cannot start or join a game. Use /buytickets (in private chat " +
                                        "@" + getBotUsername() + ") to buy tickets");
                                document.append(ticketKey, 0);
                                userDataCollection.insertOne(document);
                            }
                        }
                    } else {
                        sendMessage(chatId, "This command can only be run in a group!!!");
                    }
                }
                case "/join", "/join@Lucky_Gates_Bot" -> {
                    if (update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat()) {
                        if (currentlyActiveGames.containsKey(chatId)) {
                            Game game = currentlyActiveGames.get(chatId);
                            if (!game.hasGameStarted) {
                                Document document = new Document(idKey, fromId);
                                Document foundAddyDoc = userDataCollection.find(document).first();
                                if (foundAddyDoc != null) {
                                    int tickets = (int) foundAddyDoc.get(ticketKey);
                                    if (tickets > 0) {
                                        if (game.addPlayer(update.getMessage().getFrom())) {
                                            sendMessage(chatId, "You have successfully join the game @" + userName);
                                        } else {
                                            sendMessage(chatId, "You are already in the game @" + userName);
                                        }
                                    } else {
                                        sendMessage(chatId, "You have 0 tickets. Cannot start or join a game. Use /buytickets (in private chat " +
                                                "@" + getBotUsername() + ") to buy tickets");
                                    }
                                } else {
                                    sendMessage(chatId, "You have 0 tickets. Cannot start or join a game. Use /buytickets (in private chat " +
                                            "@" + getBotUsername() + ") to buy tickets");
                                    document.append(ticketKey, 0);
                                    userDataCollection.insertOne(document);
                                }
                            }
                        } else {
                            sendMessage(chatId, "No Games Active. Start a new one to join");
                        }
                    } else {
                        sendMessage(chatId, "This command can only be run in a group!!!");
                    }
                }
                case "/begin", "/begin@Lucky_Gates_Bot" -> {
                    if (update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat()) {
                        if (currentlyActiveGames.containsKey(chatId)) {
                            Game game = currentlyActiveGames.get(chatId);
                            if (!game.hasGameStarted) {
                                if (game.getGameInitiator() == fromId) {
                                    if (!game.beginGame()) {
                                        sendMessage(chatId, "Cannot begin the game. Not Enough Players!\nCurrent Number of Players : "
                                                + game.numberOfPlayers);
                                    }
                                } else {
                                    sendMessage(chatId, "/begin command can only be used by the person who initiated the game or the game automatically " +
                                                    "start after the join time ends and minimum number of players are found",
                                            "https://media.giphy.com/media/Lr9Y5rWFIpcsTSodLj/giphy.gif");
                                }
                            }
                        } else {
                            sendMessage(chatId, "No Games Active. Although you CAN use /Start command to start a new game XD");
                        }
                    } else {
                        sendMessage(chatId, "This command can only be run in a group!!!");
                    }
                }
                case "/mytickets", "/mytickets@Lucky_Gates_Bot" -> {
                    if (currentlyActiveGames.containsKey(chatId)) {
                        return;
                    }
                    if (!update.getMessage().getChat().isUserChat()) {
                        sendMessage(chatId, "Please use /mytickets command in private chat");
                    } else {
                        Document document = new Document(idKey, fromId);
                        Document foundAddyDoc = userDataCollection.find(document).first();
                        if (foundAddyDoc != null) {
                            int tickets = (int) foundAddyDoc.get(ticketKey);
                            sendMessage(chatId, "You currently have " + tickets + " tickets");
                        } else {
                            document.append(ticketKey, 0);
                            userDataCollection.insertOne(document);
                            sendMessage(chatId, "You have 0 tickets.");
                        }
                    }
                }
                case "/paywithticket", "/paywithticket@Lucky_Gates_Bot" -> {
                    if (update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat()) {
                        if (currentlyActiveGames.containsKey(chatId)) {
                            Game game = currentlyActiveGames.get(chatId);
                            if (game.isAcceptingEntryPayment) {
                                Document document = new Document(idKey, fromId);
                                Document foundAddyDoc = userDataCollection.find(document).first();
                                if (foundAddyDoc != null) {
                                    int tickets = (int) foundAddyDoc.get(ticketKey);
                                    if (tickets > 0) {
                                        if (game.payWithTicketForThisUser(fromId)) {
                                            tickets--;
                                            Bson updatedAddyDoc = new Document(ticketKey, tickets);
                                            Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                                            userDataCollection.updateOne(foundAddyDoc, updateAddyDocOperation);
                                        }
                                    } else {
                                        sendMessage(chatId, "@" + userName + " You have 0 tickets. Cannot " +
                                                "pay with tickets.");
                                    }
                                } else {
                                    sendMessage(chatId, "You have 0 tickets. Cannot pay with tickets");
                                    document.append(ticketKey, 0);
                                    userDataCollection.insertOne(document);
                                }
                            } else {
                                if (!game.hasGameStarted) {
                                    sendMessage(chatId, "Not accepting any entry Payments at the moment");
                                }
                            }
                        } else {
                            sendMessage(chatId, "@" + userName + " No active game. Not accepting any " +
                                    "entry payment at the moment");
                        }
                    } else {
                        sendMessage(chatId, "This command can only be run in a group!!!");
                    }
                }
                case "/buytickets", "/buytickets@Lucky_Gates_Bot" -> {
                    if (!update.getMessage().getChat().isUserChat()) {
                        sendMessage(chatId, "Please use /buytickets command in private chat @" + getBotUsername());
                    } else {
                        if (inputMsg.length != 3) {
                            sendMessage(chatId, """
                                    Proper format to use this command is (Everything space separated) :

                                    /buytickets your_BSC_Addy amount_To_Buy""");
                        } else {
                            if (playersBuyingTickets.containsKey(fromId)) {
                                sendMessage(chatId, "Please complete your current purchase before starting a new purchase");
                            } else {
                                Document searchDoc = new Document(idKey, fromId);
                                Document foundDoc = userDataCollection.find(searchDoc).first();
                                if (!playersBuyingTickets.containsValue(inputMsg[1])) {
                                    if (foundDoc != null) {
                                        int amountToBuy;
                                        try {
                                            amountToBuy = Integer.parseInt(inputMsg[2]);
                                            TicketBuyer ticketBuyer = new TicketBuyer(this, chatId, amountToBuy,
                                                    inputMsg[1], ourWallet, ANONContractAddress, joinCost);
                                            playersBuyingTickets.put(fromId, inputMsg[1]);
                                            ticketBuyers.put(chatId, ticketBuyer);
                                        } catch (Exception e) {
                                            sendMessage(chatId, """
                                                    Proper format to use this command is (Everything space separated) :

                                                    /buytickets your_BSC_Addy amount_To_Buy


                                                    Where amount has to be a number""");
                                        }
                                    } else {
                                        try {
                                            searchDoc.append(ticketKey, 0);
                                            userDataCollection.insertOne(searchDoc);
                                            int amountToBuy;
                                            try {
                                                amountToBuy = Integer.parseInt(inputMsg[2]);
                                                TicketBuyer ticketBuyer = new TicketBuyer(this, chatId, amountToBuy,
                                                        inputMsg[1], ourWallet, ANONContractAddress, joinCost);
                                                playersBuyingTickets.put(fromId, inputMsg[1]);
                                                ticketBuyers.put(chatId, ticketBuyer);
                                            } catch (Exception e) {
                                                sendMessage(chatId, """
                                                        Proper format to use this command is (Everything space separated) :

                                                        /buytickets your_BSC_Addy amount_To_Buy

                                                        Where amount has to be a number""");
                                            }
                                        } catch (Exception e) {
                                            sendMessage(chatId, "Invalid Format");
                                        }
                                    }
                                } else {
                                    sendMessage(chatId, "This wallet is already being used for a purchase. Please use different wallet address");
                                }
                            }
                        }
                    }
                }
                case "/receive", "/receive@Lucky_Gates_Bot" -> {
                    if (update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat()) {
                        if (currentlyActiveGames.containsKey(chatId)) {
                            Game game = currentlyActiveGames.get(chatId);
                            if (game.isSendingPrize) {
                                if (fromId == game.winnerId) {
                                    if (inputMsg.length == 2) {
                                        game.startPrizeSend(inputMsg[1]);
                                    } else {
                                        sendMessage(chatId, "Invalid Format. Proper Format :-\n/receive@Lucky_Gates_Bot TOMO_Address");
                                    }
                                } else {
                                    sendMessage(chatId, "You are not the winner. You cannot use this command");
                                }
                            } else {
                                if (game.hasGameStarted) {
                                    return;
                                }
                                sendMessage(chatId, "Cannot use this command at the moment. Use this command to receive prize after winning a game");
                            }
                        } else {
                            sendMessage(chatId, "Cannot use this command at the moment. Use this command to receive prize after winning a game");
                        }
                    } else {
                        sendMessage(chatId, "This command can only be run in a group!!!");
                    }
                }
                case "/transfertickets", "/transfertickets@Lucky_Gates_Bot" -> {
                    if (currentlyActiveGames.containsKey(chatId)) {
                        Game game = currentlyActiveGames.get(chatId);
                        if (game.hasGameStarted) {
                            return;
                        }
                    }
                    if (update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat()) {
                        if (update.getMessage().isReply()) {
                            if (inputMsg.length != 2) {
                                sendMessage(chatId, "Proper format to use this command is : /transfertickets@Lucky_Gates_Bot amountToTransfer");
                            } else {
                                try {
                                    int amountToTransfer = Integer.parseInt(inputMsg[1]);
                                    int ToId = Math.toIntExact(update.getMessage().getReplyToMessage().getFrom().getId());
                                    Document FromDocument = new Document(idKey, fromId);
                                    Document ToDocument = new Document(idKey, ToId);
                                    Document foundFromDoc = userDataCollection.find(FromDocument).first();
                                    Document foundToDoc = userDataCollection.find(ToDocument).first();
                                    if (foundFromDoc != null) {
                                        try {
                                            int tickets = (int) foundFromDoc.get(ticketKey);
                                            if (tickets >= amountToTransfer) {
                                                Bson updatedAddyDoc = new Document(ticketKey, tickets - amountToTransfer);
                                                Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                                                userDataCollection.updateOne(foundFromDoc, updateAddyDocOperation);
                                                if (foundToDoc != null) {
                                                    tickets = (int) foundToDoc.get(ticketKey);
                                                    updatedAddyDoc = new Document(ticketKey, tickets + amountToTransfer);
                                                    updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                                                    userDataCollection.updateOne(foundToDoc, updateAddyDocOperation);
                                                } else {
                                                    ToDocument.append(ticketKey, amountToTransfer);
                                                    userDataCollection.insertOne(ToDocument);
                                                }
                                                sendMessage(chatId, "Ticket Transfer Successful");
                                            } else {
                                                sendMessage(chatId, "You don't have enough tickets");
                                            }
                                        } catch (Exception e) {
                                            sendMessage(chatId, "Invalid. Bot Error");
                                        }
                                    } else {
                                        try {
                                            FromDocument.append(ticketKey, 0);
                                            userDataCollection.insertOne(FromDocument);
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                        sendMessage(chatId, "You don't have enough tickets");
                                    }
                                } catch (Exception e) {
                                    sendMessage(chatId, """
                                            Proper format to use this command is : /transfertickets@Lucky_Gates_Bot amountToTransfer

                                            Amount has to be a number""");
                                }
                            }
                        } else {
                            sendMessage(chatId, "This message has to be a reply type message quoting any message of the person to whom you want to " +
                                    "transfer the tickets");
                        }
                    } else {
                        sendMessage(chatId, "This command can only be run in a group!!!");
                    }
                }
                case "/addtickets", "/addtickets@Lucky_Gates_Bot" -> {
                    if (getAdminChatId().equalsIgnoreCase(String.valueOf(fromId))) {
                        if (update.getMessage().isReply()) {
                            int Id = Math.toIntExact(update.getMessage().getReplyToMessage().getFrom().getId());
                            Document document = new Document(idKey, Id);
                            Document foundDoc = userDataCollection.find(document).first();
                            int ticks = Integer.parseInt(update.getMessage().getText().trim().split(" ")[1]);
                            if (foundDoc != null) {
                                try {
                                    int tickets = (int) foundDoc.get(ticketKey) + ticks;
                                    Bson updatedAddyDoc = new Document(ticketKey, tickets);
                                    Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                                    userDataCollection.updateOne(foundDoc, updateAddyDocOperation);
                                } catch (Exception e) {
                                    sendMessage(chatId, "Invalid Format");
                                }
                            } else {
                                try {
                                    document.append(ticketKey, ticks);
                                    userDataCollection.insertOne(document);
                                } catch (Exception e) {
                                    sendMessage(chatId, "Invalid Format");
                                }
                            }
                            sendMessage(chatId, "Successfully Added " + ticks + " Ticket");
                        } else {
                            sendMessage(chatId, "This message has to be a reply type message");
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


    public void sendMessage(String chat_id, String msg, String... url) {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (url.length == 0) {
            SendMessage sendMessage = new SendMessage();
            sendMessage.setText(msg);
            sendMessage.setChatId(String.valueOf(chat_id));
            try {
                int messageId = execute(sendMessage).getMessageId();
                if (messagesForDeletion.containsKey(chat_id)) {
                    messagesForDeletion.get(chat_id).add(messageId);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            ArrayList<Integer> index = new ArrayList<>();
            for (int i = 0; i < url.length; i++) {
                index.add(i);
            }
            Collections.shuffle(index);
            SendAnimation sendAnimation = new SendAnimation();
            sendAnimation.setAnimation(new InputFile().setMedia(url[(int) (Math.random() * (url.length))]));
            sendAnimation.setCaption(msg);
            sendAnimation.setChatId(String.valueOf(chat_id));
            try {
                int messageId = execute(sendAnimation).getMessageId();
                if (messagesForDeletion.containsKey(chat_id)) {
                    messagesForDeletion.get(chat_id).add(messageId);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void sendMessageWithButton(String chat_id, String msg, String[] buttonText, String[] buttonValues) {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        SendMessage sendMessage = new SendMessage();
        sendMessage.setText(msg);
        sendMessage.setChatId(String.valueOf(chat_id));
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine = new ArrayList<>();
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
        for (int i = 0; i < buttonText.length; i++) {
            InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
            inlineKeyboardButton.setText(buttonText[i]);
            inlineKeyboardButton.setCallbackData(buttonValues[i]);
            rowInLine.add(inlineKeyboardButton);
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

    public void sendEditMessage(String chat_id, String msg, int msg_Id) {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        EditMessageText editMessageText = new EditMessageText();
        editMessageText.setMessageId(msg_Id);
        editMessageText.setChatId(String.valueOf(chat_id));
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

    public void setGotResponseFalse(String chat_id, String msg) {
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

    public void deleteGame(String chat_id) {
        deleteMessages(chat_id);
        currentlyActiveGames.remove(chat_id);
    }

    public String getAdminChatId() {
        return "607901021";
    }

    public void refund1Ticket(long playerId) {
        Document fetchDocument = new Document(idKey, playerId);
        Document gotDocument = userDataCollection.find(fetchDocument).first();
        if (gotDocument != null) {
            int tickets = (int) gotDocument.get(ticketKey);
            tickets++;
            Bson replaceDoc = new Document(ticketKey, tickets);
            Bson replaceDocOOperation = new Document("$set", replaceDoc);
            userDataCollection.updateOne(fetchDocument, replaceDocOOperation);
        }
    }

    public void playerTicketPurchaseEnded(String playerId, int numberOfTicketsToBuy, boolean didPay) {
        if (didPay) {
            Document document = new Document(idKey, playerId);
            Document foundDocument = userDataCollection.find(document).first();
            if (foundDocument != null) {
                int tickets = (int) foundDocument.get(ticketKey);
                Bson updateDoc = new Document(ticketKey, (numberOfTicketsToBuy + tickets));
                Bson updateDocOperation = new Document("$set", updateDoc);
                userDataCollection.updateOne(foundDocument, updateDocOperation);
            }
        }
        playersBuyingTickets.remove(Long.valueOf(playerId));
        ticketBuyers.remove(playerId);
    }

    private void deleteMessages(String chatId) {
        ArrayList<Integer> deletion = messagesForDeletion.get(chatId);
        while (deletion.size() > 5) {
            DeleteMessage deleteMessage = new DeleteMessage();
            deleteMessage.setChatId(chatId);
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