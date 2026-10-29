package dev.phoneagent.app.shell;

// What the shell server does for the app, and nothing more: only our app may call it (ShellServer checks the uid).
interface IShell {
    // A virtual display the size of the main one; returns its id, or -1.
    int createDisplay(int width, int height, int densityDpi);

    void releaseDisplay(int displayId);

    // Starts the activity <package>/<class> on that display.
    boolean start(String component, int displayId);

    // Sends one key (e.g. back) to that display's focused window.
    boolean key(int displayId, int keyCode);

    // Gives Phone Agent the app streaming role (role mode, see Background); an update takes it away.
    boolean grantRole();
}
