import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;

// When the application is started, the admin must send "run" message from his main account to turn on the bot.

/*Required Environment Variables :-
 *
 * LuckyGatesBotTokenA   = ?????;
 * LuckyGatesBotTokenB   = ?????;
 * mongoID               = ?????;
 * mongoPass             = ?????;
 * PrivateKey            = ?????
 * */

public class MainClass {
    private static final String ourWallet = "0x57AbCF8F01D08489236a490661aDB85c3aBB47Bc",
            ANONContractAddress = "0x9425315FeA3412fd4A0AfBfb69b99d8312dC749A";
    private static final BigInteger joinCost = new BigInteger("5000000000000"); // 5000 ANON INU
    static int minimumNumberOfPlayers = 3;

    public static void main(String[] args) {
        Logger.getRootLogger().setLevel(Level.INFO);
        BasicConfigurator.configure();
        disableAccessWarnings();
        System.setProperty("com.google.inject.internal.cglib.$experimental_asm7", "true");

        // Starting Telegram bot and Web3 services
        try {
            TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
            telegramBotsApi.registerBot(new Lucky_Gates_Bot(ourWallet, ANONContractAddress, joinCost, minimumNumberOfPlayers));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private static void disableAccessWarnings() {
        try {
            Class unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);

            Method putObjectVolatile = unsafeClass.getDeclaredMethod("putObjectVolatile", Object.class, long.class, Object.class);
            Method staticFieldOffset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field.class);

            Class loggerClass = Class.forName("jdk.internal.module.IllegalAccessLogger");
            Field loggerField = loggerClass.getDeclaredField("logger");
            Long offset = (Long) staticFieldOffset.invoke(unsafe, loggerField);
            putObjectVolatile.invoke(unsafe, loggerClass, offset, null);
        } catch (Exception ignored) {
        }
    }
}