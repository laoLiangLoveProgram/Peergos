package peergos.server;

import peergos.server.corenode.*;
import peergos.server.messages.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.storage.admin.*;
import peergos.server.util.*;
import peergos.shared.corenode.*;
import peergos.shared.mutable.*;
import peergos.shared.user.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

public class ServerMessages extends Builder {

    public static final Command<Boolean> SHOW = new Command<>("show",
            "Show messages with a user of this server",
            a -> {
                boolean usePostgres = a.getBoolean("use-postgres", false);
                SqlSupplier sqlCommands = usePostgres ?
                        new PostgresCommands() :
                        new SqliteCommands();
                ServerMessageStore store = new ServerMessageStore(getDBConnector(a, "server-messages-sql-file"),
                        sqlCommands, null, null);
                List<ServerMessage> messages = store.getMessages(a.getArg("username"));
                List<ServerConversation> conversations = ServerConversation.combine(messages);
                for (ServerConversation conv : conversations) {
                    for (ServerMessage msg : conv.messages) {
                        System.out.println(msg.summary());
                        System.out.println(msg.contents);
                    }
                    System.out.println();
                }
                return true;
            },
            Arrays.asList(
                    new Command.Arg("username", "Peergos username", true),
                    new Command.Arg("server-messages-sql-file", "The filename for the server messages datastore", true, "server-messages.sql")
            )
    );

    public static final Command<Boolean> SEND = new Command<>("send",
            "Send a message to a user of this server",
            a -> {
                boolean usePostgres = a.getBoolean("use-postgres", false);
                SqlSupplier sqlCommands = usePostgres ?
                        new PostgresCommands() :
                        new SqliteCommands();
                ServerMessageStore store = new ServerMessageStore(getDBConnector(a, "server-messages-sql-file"),
                        sqlCommands, null, null);
                String message;
                if (a.hasArg("msg-file")) {
                    try {
                        message = Files.readString(Paths.get(a.getArg("msg-file")));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } else
                    message = a.getArg("msg");
                ServerMessage msg = new ServerMessage(-1, ServerMessage.Type.FromServer, System.currentTimeMillis(),
                        message, a.getOptionalArg("reply-to").map(Long::parseLong), false);
                if (a.hasArg("usernames")) {
                    try {
                        List<String> usernames = Files.readAllLines(Paths.get(a.getArg("usernames")));
                        for (String username : usernames) {
                            store.addMessage(username, msg);
                            System.out.println("Message sent to " + username);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    String username = a.getArg("username");
                    store.addMessage(username, msg);
                    System.out.println("Message sent to " + username);
                }
                return true;
            },
            Arrays.asList(
                    new Command.Arg("usernames", "Path to file containing usernames, one per line", false),
                    new Command.Arg("username", "Peergos username", false),
                    new Command.Arg("reply-to", "Message id to reply to", false),
                    new Command.Arg("msg", "Message to send", false),
                    new Command.Arg("msg-file", "File containing message to send", false),
                    new Command.Arg("server-messages-sql-file", "The filename for the server messages datastore", true, "server-messages.sql")
            )
    );

    private static QuotaAdmin buildQuotaStore(Args a) {
        TransactionStore transactions = buildTransactionStore(a);
        DeletableContentAddressedStorage localStorage = buildLocalStorage(a, transactions);
        JdbcIpnsAndSocial rawPointers = buildRawPointers(a);
        MutablePointers localPointers = UserRepository.build(localStorage, rawPointers);
        MutablePointersProxy proxingMutable = new HttpMutablePointers(buildP2pHttpProxy(a), getPkiServerId(a));
        CoreNode core = buildCorenode(a, localStorage, transactions, rawPointers, localPointers, proxingMutable, Main.initCrypto().hasher);
        return buildSpaceQuotas(a, localStorage, core);
    }

    public static final Command<Boolean> NEW = new Command<>("new",
            "Show new messages from users of this server",
            a -> {
                ServerMessageStore store = new ServerMessageStore(getDBConnector(a, "server-messages-sql-file"),
                        getSqlCommands(a), null, null);
                QuotaAdmin quotas = buildQuotaStore(a);

                List<String> usernames = quotas.getLocalUsernames();
                for (String username : usernames) {
                    List<ServerMessage> all = store.getMessages(username);
                    List<ServerConversation> allConvs = ServerConversation.combine(all);
                    List<ServerConversation> withReply = allConvs.stream()
                            .filter(c -> c.lastMessage().type == ServerMessage.Type.FromUser)
                            .collect(Collectors.toList());
                    if (! withReply.isEmpty()) {
                        System.out.println("==================================================");
                        System.out.println("Replies from " + username);
                    }
                    for (ServerConversation conv : withReply) {
                        ServerMessage last = conv.lastMessage();
                        System.out.println(last.summary());
                        System.out.println(last.contents);
                    }
                }
                return true;
            },
            Arrays.asList(
                    new Command.Arg("server-messages-sql-file", "The filename for the server messages datastore", true, "server-messages.sql")
            )
    );

    public static final Command<Boolean> NEW_USERS = new Command<>("new-users",
            "Show new users of this server (that haven't been sent a welcome message)",
            a -> {
                ServerMessageStore store = new ServerMessageStore(getDBConnector(a, "server-messages-sql-file"),
                        getSqlCommands(a), null, null);
                QuotaAdmin quotas = buildQuotaStore(a);

                List<String> usernames = quotas.getLocalUsernames();
                for (String username : usernames) {
                    List<ServerMessage> all = store.getMessages(username);
                    if (all.stream().filter(m -> m.type == ServerMessage.Type.FromServer).findAny().isEmpty()) {
                        System.out.println(username);
                    }
                }
                return true;
            },
            Arrays.asList(
                    new Command.Arg("server-messages-sql-file", "The filename for the server messages datastore", true, "server-messages.sql")
            )
    );

    public static final Command<Boolean> ACTIVE_USERS = new Command<>("active-users",
            "Show users of this server that are active",
            a -> {
                ServerMessageStore store = new ServerMessageStore(getDBConnector(a, "server-messages-sql-file"),
                        getSqlCommands(a), null, null);
                QuotaAdmin quotas = buildQuotaStore(a);

                List<String> usernames = quotas.getLocalUsernames();
                LocalDateTime monthAgo = LocalDateTime.now().minusMonths(1);
                for (String username : usernames) {
                    List<ServerMessage> all = store.getMessages(username);
                    List<ServerConversation> allConvs = ServerConversation.combine(all);
                    boolean recent = allConvs.stream()
                            .anyMatch(c -> c.messages.stream().anyMatch(m ->
                                    m.type == ServerMessage.Type.FromUser &&
                                            m.getSendTime().isAfter(monthAgo)));
                    if (recent)
                        System.out.println(username);
                }
                return true;
            },
            Arrays.asList(
                    new Command.Arg("server-messages-sql-file", "The filename for the server messages datastore", true, "server-messages.sql")
            )
    );

    public static final Command<Boolean> SERVER_MESSAGES = new Command<>("server-msg",
            "Send and receive messages to/from users of this server",
            args -> {
                System.out.println("Run with -help to show options");
                return null;
            },
            Arrays.asList(
                    new Command.Arg("print-log-location", "Whether to print the log file location at startup", false, "false"),
                    new Command.Arg("log-to-file", "Whether to log to a file", false, "false"),
                    new Command.Arg("log-to-console", "Whether to log to the console", false, "false")
            ),
            Arrays.asList(SHOW, SEND, NEW, NEW_USERS, ACTIVE_USERS)
    );
}
