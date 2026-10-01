import android.content.ServerContext;
import android.os.Looper;
import com.corrodinggames.librocket.scripts.Debug;
import com.corrodinggames.librocket.scripts.Root;
import com.corrodinggames.rts.gameFramework.l;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/** Test-only bootstrap around unchanged Steam 1.15 classes; no game class is overridden. */
public final class OriginalHeadlessPeer {
    private static final ConcurrentLinkedQueue<Runnable> operations = new ConcurrentLinkedQueue<>();
    private static final Set<String> allowedDebug = Set.of("isNetworkGameActive", "numberOfHumanPlayers",
        "numberOfPlayerConnections", "getLocalPlayerId", "getNumberOfDesyncErrors", "getNumberOfDesyncPasses",
        "getNumberOfResyncSendsOrRecv", "setLocalPlayerName", "networkSetPortNumber", "setMultiplayerMap",
        "moveAllUnitsOnTeam");
    private static l engine;
    private static Debug debug;
    private static boolean resetClock;
    private static boolean adoptedStartedGame;

    public static void main(String[] arguments) throws Exception {
        Looper.a();
        // These are the original desktop -nodisplay/-noresources/-nomods options, plus its
        // existing null audio, null music and null graphics factories. No native UI is loaded.
        l.aU = true; l.bb = true; l.aB = true; l.aJ = true; l.ay = true;
        // Stock game.i chooses m.z for desktop when -noresources leaves bg unset. m.x is
        // the Android Bitmap renderer and requires unavailable Android native/stub APIs.
        l.bh = new com.corrodinggames.rts.gameFramework.m.z();
        Class.forName("com.corrodinggames.rts.gameFramework.a.e").getField("c").set(null,
            Class.forName("com.corrodinggames.rts.gameFramework.a.f").getConstructor().newInstance());
        com.corrodinggames.rts.gameFramework.am.a = new com.corrodinggames.rts.gameFramework.av();
        Class.forName("com.corrodinggames.rts.gameFramework.j.n").getField("d").set(null, new com.corrodinggames.rts.java.k());
        engine = l.a(new ServerContext(), new com.corrodinggames.rts.gameFramework.n() {
            public void a(String text, int level) { System.out.println("OriginalMessage " + text); }
            public void a(String title, String text) { System.out.println("OriginalMessage " + title + ": " + text); }
            public void a(String text, boolean replace) { System.out.println("OriginalLoading " + text); }
            public void a(Throwable error) { error.printStackTrace(); }
        });
        final com.corrodinggames.rts.appFramework.m pointer = new com.corrodinggames.rts.appFramework.m();
        com.corrodinggames.rts.java.d view = new com.corrodinggames.rts.java.d() {
            @Override public com.corrodinggames.rts.appFramework.m k() { return pointer; }
        };
        view.a = 1280; view.b = 720;
        engine.a(null, view, true);
        engine.b(1280, 720);
        Constructor<Debug> constructor = Debug.class.getDeclaredConstructor(Root.class);
        constructor.setAccessible(true);
        debug = constructor.newInstance(new Root());
        System.out.println("OriginalHeadlessPeer core=" + l.class.getProtectionDomain().getCodeSource().getLocation()
            + " version=" + engine.u() + " protocol=" + engine.c(false) + " nativeOriginalUi=false");
        if (arguments.length == 1 && arguments[0].equals("--boot-only")) System.exit(0);
        int port = Integer.parseInt(arguments[0]);
        ServerSocket listener = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        Thread control = new Thread(() -> {
            while (true) try {
                Socket socket = listener.accept();
                Thread worker = new Thread(() -> serve(socket), "original-peer-control");
                worker.setDaemon(true); worker.start();
            } catch (IOException error) { error.printStackTrace(); return; }
        }, "original-peer-listener");
        control.setDaemon(true); control.start();
        System.out.println("OriginalHeadlessPeer listening=" + port);
        long previousMillis = System.currentTimeMillis();
        while (true) {
            Runnable operation;
            while ((operation = operations.poll()) != null) operation.run();
            if (!engine.bX.aW) adoptedStartedGame = false;
            if (engine.bX.aW && !adoptedStartedGame) {
                // The stock Main$3 callback schedules these operations before the next outer loop.
                // Poll its unchanged start flag instead of instantiating Main's native UI callback.
                com.corrodinggames.rts.appFramework.n.r();
                if (engine.bL == null || !engine.bL.W) { engine.bX.af(); }
                else {
                    engine.bX.bd = true; engine.bH = false; engine.aq = false;
                    adoptedStartedGame = true;
                    System.out.println("OriginalHeadlessPeer map started via stock loader");
                }
                resetClock = true;
            }
            long now = System.nanoTime();
            long nowMillis = System.currentTimeMillis();
            if (resetClock) { previousMillis = nowMillis; resetClock = false; }
            int milliseconds = (int)Math.max(0L, Math.min(Integer.MAX_VALUE, nowMillis - previousMillis));
            previousMillis = nowMillis;
            // Exact stock Slick u.update/render conversion, including the float literal. The
            // unchanged game.i loop applies its own original clamp/network accumulator rules.
            engine.a(milliseconds * 0.060000002f, milliseconds);
            long period = engine.bQ.batterySaving ? 32_258_064L : engine.bQ.highRefreshRate ? 3_333_333L : 16_393_442L;
            LockSupport.parkNanos(Math.max(1L, period - (System.nanoTime() - now)));
        }
    }

    private static void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(120_000);
            String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            boolean function = line != null && line.startsWith("function ");
            if (line == null || !(function || line.startsWith("script "))) throw new IllegalArgumentException("Expected function or script");
            String expression = line.substring(function ? 9 : 7);
            CompletableFuture<Object> result = new CompletableFuture<>();
            operations.add(() -> { try { result.complete(evaluate(expression)); } catch (Throwable error) { result.completeExceptionally(error); } });
            String response;
            try {
                Object value = result.get(120, TimeUnit.SECONDS);
                response = function ? "ok\n" + (value == null ? "<NULL>" : value) + "\0" : "done";
            } catch (Exception error) { response = "crash\n" + error + (function ? "\0" : ""); }
            socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { error.printStackTrace(); }
    }

    private static Object evaluate(String expression) throws Exception {
        int open = expression.indexOf('(');
        if (open < 0 || !expression.endsWith(")")) throw new IllegalArgumentException("Expected method call");
        String name = expression.substring(0, open);
        List<String> arguments = split(expression.substring(open + 1, expression.length() - 1));
        if (name.startsWith("debug.")) {
            String methodName = name.substring(6);
            if (!allowedDebug.contains(methodName)) throw new IllegalArgumentException("Operation is outside the compatibility probe");
            Method method = Arrays.stream(Debug.class.getMethods()).filter(m -> m.getName().equals(methodName) && m.getParameterCount() == arguments.size()).findFirst().orElseThrow();
            Object[] values = new Object[arguments.size()];
            for (int i = 0; i < values.length; i++) values[i] = convert(arguments.get(i), method.getParameterTypes()[i]);
            return method.invoke(debug, values);
        }
        if (name.equals("hostStartWithPasswordAndMods")) {
            engine.bX.b("starting new");
            engine.bX.n = (String)convert(arguments.get(1), String.class);
            engine.bX.o = (boolean)convert(arguments.get(2), boolean.class);
            engine.bX.q = (boolean)convert(arguments.get(0), boolean.class);
            return engine.bX.b(false);
        }
        if (name.equals("joinServer")) {
            String address = (String)convert(arguments.get(0), String.class);
            Socket connection = (Socket)Class.forName("com.corrodinggames.rts.gameFramework.j.ad")
                .getMethod("b", String.class, boolean.class).invoke(null, address, false);
            engine.bX.b("starting new");
            return engine.bX.a(connection);
        }
        if (name.equals("mp.refreshUI")) return null; // No display exists; no engine operation is omitted.
        if (name.equals("mp.multiplayerStart")) {
            if (!engine.bX.C) throw new IllegalStateException("Not the host");
            switch (engine.bX.ay.a.ordinal()) {
                case 0: engine.bX.az = "maps/skirmish/" + engine.bX.ay.b; break;
                case 1: engine.bX.az = "/SD/rusted_warfare_maps/" + engine.bX.ay.b; break;
                case 2: engine.bX.az = null; break;
                default: throw new IllegalStateException("Unknown stock map type");
            }
            return engine.bX.ae();
        }
        throw new IllegalArgumentException("Unsupported compatibility operation: " + name);
    }

    private static Object convert(String value, Class<?> type) {
        if (value.equals("null")) return null;
        if (type == String.class) {
            if (value.length() < 2 || (value.charAt(0) != '\'' && value.charAt(0) != '"')) throw new IllegalArgumentException("Expected quoted text");
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\");
        }
        if (type == int.class) return Integer.parseInt(value);
        if (type == float.class) return Float.parseFloat(value);
        if (type == boolean.class && (value.equals("true") || value.equals("false"))) return Boolean.parseBoolean(value);
        throw new IllegalArgumentException("Unsupported argument type");
    }

    private static List<String> split(String source) {
        if (source.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        int start = 0; char quote = 0; boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && quote != 0) { escaped = true; continue; }
            if (quote != 0) { if (c == quote) quote = 0; }
            else if (c == '\'' || c == '"') quote = c;
            else if (c == ',') { result.add(source.substring(start, i).trim()); start = i + 1; }
        }
        if (quote != 0) throw new IllegalArgumentException("Unclosed argument quote");
        result.add(source.substring(start).trim());
        return result;
    }
}
