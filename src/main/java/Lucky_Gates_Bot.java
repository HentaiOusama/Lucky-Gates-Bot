import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class Lucky_Gates_Bot extends org.telegram.telegrambots.bots.TelegramLongPollingBot {

    public HashMap<Long, Game> currentlyActiveGames = new HashMap<>();
    boolean gotResponse = false;
    String responseMsg = "";
    String callbackQueryId = "";
    int responseMsgId = 0;
    int replier = 0;
    final String mongoDBUri;
    final String databaseName = "Lucky-Gates-Bot-Database";
    final String idKey = "UserID", ticketKey = "Tickets";
    MongoClient mongoClient;
    MongoDatabase mongoDatabase;
    MongoCollection userDataCollection;
    boolean shouldRunGame = false;

    // Constructor
    public Lucky_Gates_Bot() {
        mongoDBUri = "mongodb+srv://" + System.getenv("LuckyGatesMonoID") + ":" +
                System.getenv("LuckyGatesMonoPass") + "@hellgatesbotcluster.zm0r5.mongodb.net/test";
        MongoClientURI mongoClientURI = new MongoClientURI(mongoDBUri);
        mongoClient = new MongoClient(mongoClientURI);
        mongoDatabase = mongoClient.getDatabase(databaseName);
        userDataCollection = mongoDatabase.getCollection("UserTickets");
    }



    @Override
    public void onUpdateReceived(Update update) {

        if(update.hasMessage()) {
            if(update.getMessage().getChatId() == getAdminChatId()) {
                if(!shouldRunGame && update.getMessage().hasText()) {
                    if(update.getMessage().getText().equalsIgnoreCase("run")) {
                        shouldRunGame = true;
                    }
                }
                if(update.getMessage().hasAnimation()) {
                    System.out.println(update.getMessage().getAnimation().getFileId());
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
            String[] inputMsg = update.getMessage().getText().trim().split(" ");
            switch (inputMsg[0]) {

                case "/startgame":
                case "/startgame@Lucky_Gates_Bot": {
                    SendMessage sendMessage = new SendMessage();
                    boolean shouldSend = true;
                    if (update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat()) {
                        long chat_id = update.getMessage().getChatId();
                        if (currentlyActiveGames.containsKey(chat_id)) {
                            sendMessage.setText("A game is already running. Please wait for current game to end to start a new one or you can join the current game");
                        } else {
                            Document document = new Document(idKey, update.getMessage().getFrom().getId());
                            Document foundAddyDoc = (Document) userDataCollection.find(document).first();
                            if(foundAddyDoc != null) {
                                Game newGame;
                                try {
                                    int tickets = (int) foundAddyDoc.get(ticketKey);
                                    if(tickets > 0) {
                                        sendMessage(chat_id, "Initiating a new Game!!!");
                                        newGame = new Game(this, chat_id, update.getMessage().getFrom().getId());
                                        newGame.addPlayer(update.getMessage().getFrom());
                                        currentlyActiveGames.put(chat_id, newGame);
                                        SendAnimation sendAnimation = new SendAnimation();
                                        sendAnimation.setAnimation("https://media.giphy.com/media/3ov9jYd9chmwCNQl8c/giphy.gif");
                                        sendAnimation.setCaption("New game has been created. Please gather at least 5 players within 6 minutes for game to begin.");
                                        sendAnimation.setChatId(update.getMessage().getChatId());
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
                    sendMessage.setChatId(update.getMessage().getChatId());
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
                        Document document = new Document(idKey, update.getMessage().getFrom().getId());
                        Document foundAddyDoc = (Document) userDataCollection.find(document).first();
                        if(foundAddyDoc != null) {
                            int tickets = (int) foundAddyDoc.get(ticketKey);
                            if(tickets > 0) {
                                if (currentlyActiveGames.containsKey(update.getMessage().getChatId())) {
                                    Game game = currentlyActiveGames.get(update.getMessage().getChatId());
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
                    sendMessage.setChatId(update.getMessage().getChatId());
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
                        if (currentlyActiveGames.containsKey(update.getMessage().getChatId())) {
                            Game game = currentlyActiveGames.get(update.getMessage().getChatId());
                            if(game.hasGameStarted){
                                sendMessage.setText("Cannot begin. The current has already begun!");
                            } else {
                                if (game.getGameInitiator() == update.getMessage().getFrom().getId()) {
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
                                        sendAnimation.setChatId(update.getMessage().getChatId());
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
                    sendMessage.setChatId(update.getMessage().getChatId());
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
                        Document getAddyDoc = new Document(idKey, update.getMessage().getFrom().getId());
                        Document foundAddyDoc = (Document) userDataCollection.find(getAddyDoc).first();
                        if(foundAddyDoc != null) {
                            int tickets = (int) foundAddyDoc.get(ticketKey);
                            sendMessage.setText("You currently have " + tickets + " tickets");
                        } else {
                            sendMessage.setText("You haven't registered with any wallet yet. Only registered uses can have tickets.");
                        }
                    }
                    sendMessage.setChatId(update.getMessage().getChatId());
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
                        if (currentlyActiveGames.containsKey(update.getMessage().getChatId())) {
                            Document document = new Document(idKey, update.getMessage().getFrom().getId());
                            Document foundAddyDoc = (Document) userDataCollection.find(document).first();
                            if(foundAddyDoc != null) {
                                int tickets = (int) foundAddyDoc.get(ticketKey);
                                if(tickets > 0) {
                                    Game game = currentlyActiveGames.get(update.getMessage().getChatId());
                                    if(game.payWithTicketForThisUser(update.getMessage().getFrom().getId())){
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
                    sendMessage.setChatId(update.getMessage().getChatId());
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
                    sendMessage.setText("Please talk with @oregazembutoushiisuru to buy tickets");
                    sendMessage.setChatId(update.getMessage().getChatId());
                    try {
                        execute(sendMessage);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }
                    break;
                }

                case "/exchange":
                case "/exchange@Lucky_Gates_Bot": {
                    SendMessage sendMessage = new SendMessage();
                    sendMessage.setText("Please talk with @oregazembutoushiisuru to get crts in exchange for tickets");
                    sendMessage.setChatId(update.getMessage().getChatId());
                    try {
                        execute(sendMessage);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }
                    break;
                }

                case "/addtickets":
                case "/addtickets@Lucky_Gates_Bot": {
                    if(update.getMessage().getFrom().getId() != getAdminChatId()) {
                        sendMessage(update.getMessage().getChatId(), "You are not allowed to use this command");
                        return;
                    } else {
                        if(update.getMessage().isReply()) {
                            int Id = update.getMessage().getReplyToMessage().getFrom().getId();
                            Document document = new Document(idKey, Id);
                            Document foundDoc = (Document) userDataCollection.find(document).first();
                            if(foundDoc != null) {
                                try {
                                    int tickets = (int) foundDoc.get(ticketKey) + (Integer.parseInt(update.getMessage().getText().trim().split(" ")[1]));
                                    Bson updatedAddyDoc = new Document(ticketKey, tickets);
                                    Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                                    userDataCollection.updateOne(foundDoc, updateAddyDocOperation);
                                } catch (Exception e) {
                                    sendMessage(update.getMessage().getChatId(), "Invalid Format");
                                }
                            } else {
                                try {
                                    document.append(ticketKey, (Integer.parseInt(update.getMessage().getText().trim().split(" ")[1])));
                                    userDataCollection.insertOne(document);
                                } catch (Exception e) {
                                    sendMessage(update.getMessage().getChatId(), "Invalid Format");
                                }
                            }
                        } else {
                            sendMessage(update.getMessage().getChatId(), "This message has to be a reply type message");
                        }
                    }
                    break;
                }

                case "/removetickets":
                case "/removetickets@Lucky_Gates_Bot": {
                    if(update.getMessage().getFrom().getId() != getAdminChatId()) {
                        sendMessage(update.getMessage().getChatId(), "You are not allowed to use this command");
                        return;
                    } else {
                        if(update.getMessage().isReply()) {
                            int Id = update.getMessage().getReplyToMessage().getFrom().getId();
                            Document document = new Document(idKey, Id);
                            Document foundDoc = (Document) userDataCollection.find(document).first();
                            if(foundDoc != null) {
                                try {
                                    int ticketsToRemove = (Integer.parseInt(update.getMessage().getText().trim().split(" ")[1]));
                                    int tickets = (int) foundDoc.get(ticketKey);
                                    if(tickets >= ticketsToRemove) {
                                        Bson updatedAddyDoc = new Document(ticketKey, (tickets-ticketsToRemove));
                                        Bson updateAddyDocOperation = new Document("$set", updatedAddyDoc);
                                        userDataCollection.updateOne(foundDoc, updateAddyDocOperation);
                                        sendMessage(update.getMessage().getChatId(), "Successfully removed " + ticketsToRemove + " tickets");
                                    } else {
                                        sendMessage(update.getMessage().getChatId(), "This user does not have " + ticketsToRemove + " tickets");
                                    }
                                } catch (Exception e) {
                                    sendMessage(update.getMessage().getChatId(), "Invalid Format");
                                }
                            } else {
                                try {
                                    document.append(ticketKey, 0);
                                    userDataCollection.insertOne(document);
                                    sendMessage(update.getMessage().getChatId(), "This user does not have any tickets tickets");
                                } catch (Exception e) {
                                    sendMessage(update.getMessage().getChatId(), "Invalid Format");
                                }
                            }
                        } else {
                            sendMessage(update.getMessage().getChatId(), "This message has to be a reply type message");
                        }
                    }
                    break;
                }

                default: {
                    String msg = update.getMessage().getText().trim();
                    if(msg.endsWith("@Lucky_Gates_Bot") && msg.startsWith("/")) {
                        SendMessage sendMessage = new SendMessage();
                        sendMessage.setText("No such command exists");
                        sendMessage.setChatId(update.getMessage().getChatId());
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
                execute(sendMessage);
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
                execute(sendAnimation);
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
            execute(sendMessage);
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

    public void addTicketsForPlayer(int playerId, int numberOfTicketsToAdd) {
        Document document = new Document(idKey, playerId);
        Document foundDocument = (Document) userDataCollection.find(document).first();
        if(foundDocument != null) {
            int tickets = (int) foundDocument.get(ticketKey);
            Bson updateDoc = new Document(ticketKey, (numberOfTicketsToAdd + tickets));
            Bson updateDocOperation = new Document("$set", updateDoc);
            userDataCollection.updateOne(foundDocument, updateDocOperation);
        } else {
            document.append(ticketKey, numberOfTicketsToAdd);
            userDataCollection.insertOne(document);
        }
    }
}