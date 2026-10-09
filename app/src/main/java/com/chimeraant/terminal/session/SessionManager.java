package com.chimeraant.terminal.session;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.chimeraant.terminal.root.ProotInstaller;
import com.chimeraant.terminal.root.RootManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns every live sandbox.
 *
 * Sandboxes are independent processes on independent PTYs. Only the currently
 * visible one is attached to the UI, and hidden ones keep running without
 * redraws, which is what keeps many sessions cheap on modest hardware.
 */
public class SessionManager {

    public interface Observer {
        void onSessionsChanged();
    }

    private static volatile SessionManager instance;

    private final Context context;
    private final EnvironmentBuilder environmentBuilder;
    private final RootManager rootManager;
    private final List<TerminalSession> sessions = new ArrayList<>();
    private final List<Observer> observers = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TerminalSession activeSession;

    private SessionManager(Context context) {
        this.context = context.getApplicationContext();
        this.environmentBuilder = new EnvironmentBuilder(this.context);
        this.rootManager = new RootManager(this.context);
    }

    public static SessionManager get(Context context) {
        if (instance == null) {
            synchronized (SessionManager.class) {
                if (instance == null) {
                    instance = new SessionManager(context);
                }
            }
        }
        return instance;
    }

    public EnvironmentBuilder getEnvironmentBuilder() {
        return environmentBuilder;
    }

    public RootManager getRootManager() {
        return rootManager;
    }

    public void addObserver(Observer observer) {
        observers.add(observer);
    }

    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    public synchronized List<TerminalSession> getSessions() {
        return new ArrayList<>(sessions);
    }

    public synchronized int getSessionCount() {
        return sessions.size();
    }

    public synchronized TerminalSession getActiveSession() {
        return activeSession;
    }

    public synchronized void setActiveSession(TerminalSession session) {
        this.activeSession = session;
        notifyObservers();
    }

    public TerminalSession createSession(String name) {
        return createSession(name, false, false);
    }

    /**
     * @param rootShell launch the shell with root (device su or proot)
     * @param useProot  force the proot sandbox path
     */
    public synchronized TerminalSession createSession(String name, boolean rootShell, boolean useProot) {
        String sandboxId = EnvironmentBuilder.newSandboxId();
        String displayName = name != null ? name : "sandbox " + (sessions.size() + 1);

        try {
            environmentBuilder.prepare(sandboxId, displayName);
        } catch (IOException e) {
            // Still attempt to start; the shell will report the failure.
        }

        boolean actuallyRoot = rootShell && rootManager.isRootEnabled();
        boolean prootMode = useProot && rootManager.hasProot();

        Map<String, String> env = new HashMap<>(
                environmentBuilder.buildEnvironment(sandboxId, actuallyRoot || prootMode));

        String[] command;
        if (prootMode) {
            ProotInstaller installer = new ProotInstaller(context);
            command = installer.prootLoginCommand(new String[]{"/bin/sh"});
        } else if (actuallyRoot && rootManager.getSuPath() != null) {
            command = rootManager.rootedCommandFor(new String[]{"/system/bin/sh"});
        } else {
            command = new String[]{"/system/bin/sh", "-l"};
        }

        TerminalSession.Config config = new TerminalSession.Config(
                displayName, command, environmentBuilder.getHome(sandboxId).getAbsolutePath(), env);

        TerminalSession session = new TerminalSession(config, 80, 24, sessionListener);
        sessions.add(session);
        if (activeSession == null) {
            activeSession = session;
        }
        notifyObservers();
        return session;
    }

    public synchronized void closeSession(TerminalSession session) {
        session.destroy();
        sessions.remove(session);
        if (activeSession == session) {
            activeSession = sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
        }
        notifyObservers();
    }

    public synchronized void closeAll() {
        for (TerminalSession session : new ArrayList<>(sessions)) {
            session.destroy();
        }
        sessions.clear();
        activeSession = null;
        notifyObservers();
    }

    public synchronized int runningCount() {
        int count = 0;
        for (TerminalSession session : sessions) {
            if (!session.isFinished()) count++;
        }
        return count;
    }

    private final TerminalSession.Listener sessionListener = new TerminalSession.Listener() {
        @Override
        public void onSessionChanged(TerminalSession session) {
            // UI updates are pull-based; nothing to push for every byte.
        }

        @Override
        public void onSessionFinished(TerminalSession session) {
            notifyObservers();
        }
    };

    private void notifyObservers() {
        mainHandler.post(() -> {
            for (Observer observer : observers) {
                observer.onSessionsChanged();
            }
        });
    }
}
