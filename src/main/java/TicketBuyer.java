import org.web3j.abi.TypeDecoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.http.HttpService;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

public class TicketBuyer {
    Lucky_Gates_Bot lucky_gates_bot;
    String chatId;
    int numberOfTicketsToBuy;
    String addyOfPlayer;

    final String ANONContractAddress;
    final String ourWallet;
    final BigInteger TicketCost;
    final BigInteger TotalAmountToSpend;
    private final Web3j web3j;
    private String transactionHash = null;
    private volatile boolean checked = false;

    TicketBuyer(Lucky_Gates_Bot lucky_gates_bot, String chatId, int numberOfTicketsToBuy, String addyOfPlayer, String ourWallet,
                String ANONContractAddress, BigInteger TicketCost) {
        this.chatId = chatId;
        this.lucky_gates_bot = lucky_gates_bot;
        this.numberOfTicketsToBuy = numberOfTicketsToBuy;
        this.addyOfPlayer = addyOfPlayer;
        this.ourWallet = ourWallet;
        this.ANONContractAddress = ANONContractAddress;
        this.TicketCost = TicketCost;
        TotalAmountToSpend = TicketCost.multiply(new BigInteger(Integer.toString(numberOfTicketsToBuy)));
        web3j = Web3j.build(new HttpService("https://bsc-dataseed1.defibit.io/"));
        thread.start();
    }

    Thread thread = new Thread() {
        @Override
        public void run() {
            super.run();

            BigInteger startBlockNumber;
            try {
                startBlockNumber = web3j.ethBlockNumber().send().getBlockNumber();
            } catch (IOException e) {
                e.printStackTrace();
                lucky_gates_bot.sendMessage(chatId, "Bot encountered an error. Please try again later...");
                return;
            }
            lucky_gates_bot.sendMessage(chatId, "You must send " + TotalAmountToSpend.divide(new BigInteger("1000000000")) +
                    " ANON INU within 5 minutes from your REGISTERED wallet to the following address : \n\n");
            lucky_gates_bot.sendMessage(chatId, ourWallet);
            lucky_gates_bot.sendMessage(chatId, "Transaction will be considered valid only if it's block number is greater than " +
                    startBlockNumber);
            Instant paymentEndInstant = Instant.now().plus(5, ChronoUnit.MINUTES);

            boolean didPay = false;
            Instant paymentEndInstant2 = paymentEndInstant.plus(30, ChronoUnit.SECONDS);

            while (Instant.now().compareTo(paymentEndInstant2) < 0) {
                if (transactionHash != null && !checked) {
                    try {
                        Optional<Transaction> optional = web3j.ethGetTransactionByHash(transactionHash).send().getTransaction();
                        if (optional.isPresent()) {
                            TransactionData transactionData = splitInputData(optional.get());
                            checked = true;
                            if (!transactionData.methodName.equalsIgnoreCase("Useless")) {
                                lucky_gates_bot.sendMessage(chatId, "Invalid transaction...");
                            } else if (transactionData.blockNumber.compareTo(startBlockNumber) <= 0) {
                                lucky_gates_bot.sendMessage(chatId, "Invalid block number of the transaction...");
                            } else if (!transactionData.fromAddress.equalsIgnoreCase(addyOfPlayer)) {
                                lucky_gates_bot.sendMessage(chatId, "This transaction is sent from a different account.");
                            } else if (!transactionData.toAddress.equalsIgnoreCase(ourWallet)) {
                                lucky_gates_bot.sendMessage(chatId, "This transaction was not sent to bot wallet.");
                            } else if (transactionData.value.compareTo(TotalAmountToSpend) < 0) {
                                lucky_gates_bot.sendMessage(chatId, "Insufficient Amount transferred");
                            } else {
                                didPay = true;
                                break;
                            }
                        } else {
                            lucky_gates_bot.sendMessage(chatId, "Transaction with given hash not found. It is possible that " +
                                    "transaction is still pending. Please use the /check command again after the transaction is confirmed.");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                wait500ms();
            }

            if(didPay) {
                lucky_gates_bot.sendMessage(chatId, "Payment confirmed. Successfully added tickets to your account");
                lucky_gates_bot.playerTicketPurchaseEnded(chatId, numberOfTicketsToBuy, true);
            } else {
                lucky_gates_bot.sendMessage(chatId, "We were unable to confirm you payment. This purchase has been cancelled");
                lucky_gates_bot.playerTicketPurchaseEnded(chatId, numberOfTicketsToBuy, false);
            }

        }
    };

    public TransactionData splitInputData(Transaction transaction) throws Exception {
        String inputData = transaction.getInput();
        TransactionData currentTransactionData = new TransactionData();
        String method = inputData.substring(0, 10);
        currentTransactionData.methodName = method;
        currentTransactionData.blockNumber = transaction.getBlockNumber();

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

    public void setTransactionHash(String hash) {
        transactionHash = hash;
        checked = false;
    }
}
