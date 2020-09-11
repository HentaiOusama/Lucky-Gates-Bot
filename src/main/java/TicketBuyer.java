import io.reactivex.disposables.Disposable;
import org.apache.log4j.Logger;
import org.web3j.abi.TypeDecoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.contracts.eip20.generated.ERC20;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.Web3ClientVersion;
import org.web3j.protocol.websocket.WebSocketClient;
import org.web3j.protocol.websocket.WebSocketService;
import org.web3j.tx.ClientTransactionManager;
import org.web3j.tx.gas.ContractGasProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class TicketBuyer {
    Lucky_Gates_Bot lucky_gates_bot;
    long chat_id;
    int numberOfTicketsToBuy;
    String addyOfPlayer;
    Logger logger = Logger.getLogger(TicketBuyer.class);
    boolean shouldTryToEstablishConnection = true;


    final String CRTSContractAddress;
    final String ourWallet;
    final BigInteger TicketCost;
    final BigInteger TotalAmountToSpend;
    private final ArrayList<String> webSocketUrls = new ArrayList<>();
    private WebSocketService webSocketService;
    private Web3j web3j;
    private Disposable disposable = null;
    private ArrayList<Transaction> allCRTSTransactions = new ArrayList<>();
    private ArrayList<Log> flowableTransactionLog = new ArrayList<>();
    private AtomicInteger numberOfTransactionsFetched = new AtomicInteger(0);
    private int numberOfTransactionsExamined = 0;

    TicketBuyer(Lucky_Gates_Bot lucky_gates_bot, long chat_id, int numberOfTicketsToBuy, String addyOfPlayer, String ourWallet,
                String CRTSContractAddress, BigInteger TicketCost) {

        webSocketUrls.add("wss://ws.tomochain.com");

        this.chat_id = chat_id;
        this.lucky_gates_bot = lucky_gates_bot;
        this.numberOfTicketsToBuy = numberOfTicketsToBuy;
        this.addyOfPlayer = addyOfPlayer;
        this.ourWallet = ourWallet;
        this.CRTSContractAddress = CRTSContractAddress;
        this.TicketCost = TicketCost;
        TotalAmountToSpend = TicketCost.multiply(new BigInteger(Integer.toString(numberOfTicketsToBuy)));
        thread.start();
    }

    Thread thread = new Thread() {
        @Override
        public void run() {
            super.run();
            boolean val = buildNewConnectionToTomoNetwork(true);

            if(!val) {
                lucky_gates_bot.sendMessage(chat_id, "There was an error while purchasing the tickets. Please try again later.");
                lucky_gates_bot.playerTicketPurchaseEnded((int) chat_id, numberOfTicketsToBuy, true);
                try {
                    join();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            System.out.println("\n\n\n\nIn run = " + val + "\n\n\n\n");
            lucky_gates_bot.sendMessage(chat_id, "You must send " + TotalAmountToSpend.divide(new BigInteger("1000000000000000000")) +
                    " CRTS within 3 minutes from your REGISTERED wallet to the following address : \n\n");
            lucky_gates_bot.sendMessage(chat_id, ourWallet);
            Instant paymentEndInstant = Instant.now().plus(3, ChronoUnit.MINUTES);

            boolean didPay = false;
            Instant paymentEndInstant2 = paymentEndInstant.plus(30, ChronoUnit.SECONDS);
            boolean payIns2 = true;

            OUTER :
            while (Instant.now().compareTo(paymentEndInstant2) < 0) {
                while (numberOfTransactionsExamined < numberOfTransactionsFetched.get() && numberOfTransactionsFetched.get() != 0) {
                    try {
                        TransactionData transactionData = splitInputData(allCRTSTransactions.get(numberOfTransactionsExamined));
                        boolean condition = !transactionData.methodName.equalsIgnoreCase("Useless") &&
                                transactionData.toAddress.equalsIgnoreCase(ourWallet) &&
                                transactionData.value.compareTo(TotalAmountToSpend) >= 0 &&
                                addyOfPlayer.equalsIgnoreCase(transactionData.fromAddress);
                        if(condition) {
                            didPay = true;
                            break OUTER;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    numberOfTransactionsExamined++;
                }
                if(shouldTryToEstablishConnection) {
                    buildNewConnectionToTomoNetwork(false);
                }
                wait500ms();
                if(payIns2 && Instant.now().compareTo(paymentEndInstant) > 0) {
                    payIns2 = false;
                    lucky_gates_bot.sendMessage(chat_id, "The payment time has ended. Any payments made from this point onwards will not be " +
                            "taken into account. The bot will now try to verify if a payment has been made or not.");
                }
            }
            disposable.dispose();

            if(didPay) {
                lucky_gates_bot.sendMessage(chat_id, "Payment confirmed. Successfully added tickets to your account");
                lucky_gates_bot.playerTicketPurchaseEnded((int) chat_id, numberOfTicketsToBuy, true);
            } else {
                lucky_gates_bot.sendMessage(chat_id, "We were unable to confirm you payment. This purchase has been cancelled");
                lucky_gates_bot.playerTicketPurchaseEnded((int) chat_id, numberOfTicketsToBuy, false);
            }

        }
    };



    public boolean buildNewConnectionToTomoNetwork(boolean shouldSendMessage) {
        int count = 0;
        if(shouldSendMessage) {
            lucky_gates_bot.sendMessage(chat_id, "Connecting to TOMO Network to read transactions. Please be patient. This can take from few" +
                    " seconds to few minutes");
        }
        allCRTSTransactions = new ArrayList<>();
        flowableTransactionLog = new ArrayList<>();
        numberOfTransactionsFetched = new AtomicInteger(0);
        numberOfTransactionsExamined = 0;
        if(disposable != null) {
            disposable.dispose();
            webSocketService.close();
            System.out.println("Reconnecting to Web3");
        } else {
            System.out.println("Connecting to Web3");
        }
        shouldTryToEstablishConnection = true;
        while (shouldTryToEstablishConnection && count < 6) {
            count++;
            try {
                Collections.shuffle(webSocketUrls);
                shouldTryToEstablishConnection = false;
                WebSocketClient webSocketClient = new WebSocketClient(new URI(webSocketUrls.get(0))) {
                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        super.onClose(code, reason, remote);
                        logger.info(chat_id + " : WebSocket connection to " + uri + " closed successfully " + reason);
                    }

                    @Override
                    public void onError(Exception e) {
                        super.onError(e);
                        e.printStackTrace();
                        setShouldTryToEstablishConnection();
                        logger.error(chat_id + " : WebSocket connection to " + uri + " failed with error");
                        System.out.println("Trying again");
                    }
                };
                webSocketService = new WebSocketService(webSocketClient, true);
                webSocketService.connect();
            } catch (Exception e) {
                e.printStackTrace();
            }
            wait500ms();
            wait500ms();
            wait500ms();
            wait500ms();
        }

        try {
            web3j =  Web3j.build(webSocketService);
            Web3ClientVersion web3ClientVersion = web3j.web3ClientVersion().send();
            System.out.println("Game's Chat ID : " + chat_id + "\nWeb3ClientVersion : " + web3ClientVersion.getWeb3ClientVersion());
            ClientTransactionManager transactionManager = new ClientTransactionManager(web3j, CRTSContractAddress);
            ERC20 CRTSToken = ERC20.load(CRTSContractAddress, web3j, transactionManager, new ContractGasProvider() {
                @Override
                public BigInteger getGasPrice(String s) {
                    return BigInteger.valueOf(45L);
                }

                @Override
                public BigInteger getGasPrice() {
                    return BigInteger.valueOf(45L);
                }

                @Override
                public BigInteger getGasLimit(String s) {
                    return BigInteger.valueOf(65000L);
                }

                @Override
                public BigInteger getGasLimit() {
                    return BigInteger.valueOf(65000L);
                }
            });
            EthFilter CRTSContractFilter = new EthFilter(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST, CRTSContractAddress);
            // This statement will receive all transactions that have been made and as they are made on our provided contract address in the filter
            disposable = web3j.ethLogFlowable(CRTSContractFilter).subscribe(log -> {
                if(numberOfTransactionsFetched.get() != 0) {
                    String hash = log.getTransactionHash(); // Here we obtain transaction hash of transaction from the log that we get from subscribe
                    String prevHash = flowableTransactionLog.get(numberOfTransactionsFetched.get() - 1).getTransactionHash();

                    /*The reason as to why we are comparing to prev hash is that a lot of transactions (which includes all transfer transactions),
                     * there will be two logs for each under same transaction. 1st log (for transfer transaction) will be for shot or survive and 2nd
                     * log will be for transfer. It is very important to understand this for further use of these logs.*/
                    if(!hash.equals(prevHash)) {
                        flowableTransactionLog.add(log);
                        System.out.println("Chat ID : " + chat_id + " - Trx :  " + log.getTransactionHash());
                        Optional<Transaction> trx = web3j.ethGetTransactionByHash(hash).send().getTransaction();
                        trx.ifPresent(transaction -> allCRTSTransactions.add(transaction));
                        numberOfTransactionsFetched.getAndIncrement();
                    }
                } else {
                    String hash = log.getTransactionHash();
                    flowableTransactionLog.add(log);
                    System.out.println("Chat ID : " + chat_id + " - Trx :  " + log.getTransactionHash());
                    Optional<Transaction> trx = web3j.ethGetTransactionByHash(hash).send().getTransaction();
                    trx.ifPresent(transaction -> allCRTSTransactions.add(transaction));
                    numberOfTransactionsFetched.getAndIncrement();
                }
            }, throwable -> {
                throwable.printStackTrace();
                webSocketService.close();
                webSocketService.connect();
            });
            System.out.println("\n\n\n\n\n\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return !shouldTryToEstablishConnection;
    }

    public void setShouldTryToEstablishConnection() {
        shouldTryToEstablishConnection = true;
    }

    public TransactionData splitInputData(Transaction transaction) throws Exception {
        String inputData = transaction.getInput();
        TransactionData currentTransactionData = new TransactionData();
        String method = inputData.substring(0, 10);
        currentTransactionData.methodName = method;

        // If method is transfer method
        if(method.equalsIgnoreCase("0xa9059cbb")) {
            currentTransactionData.fromAddress = transaction.getFrom().toLowerCase();
            Method refMethod = TypeDecoder.class.getDeclaredMethod("decode",String.class,int.class,Class.class);
            refMethod.setAccessible(true);
            Address toAddress = (Address) refMethod.invoke(null,inputData.substring(10, 74),0,Address.class);
            Uint256 amount = (Uint256) refMethod.invoke(null,inputData.substring(74),0,Uint256.class);
            currentTransactionData.toAddress = toAddress.toString().toLowerCase();
            currentTransactionData.value = amount.getValue();
            System.out.println(currentTransactionData.methodName + " " + currentTransactionData.fromAddress + " " + currentTransactionData.toAddress + " " +
                    currentTransactionData.value);
        } else {
            currentTransactionData.methodName = "Useless";
        }
        return currentTransactionData;
    }

    public void wait500ms() {
        try {
            Thread.sleep(500);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
