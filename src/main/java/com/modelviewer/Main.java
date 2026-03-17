package com.modelviewer;

import com.modelviewer.ui.MainWindow;
import javafx.application.Application;

/**
 * Application entry point.
 *
 * Delegates directly to JavaFX's Application launcher, which creates the
 * JavaFX Application Thread, calls MainWindow.init() and MainWindow.start().
 *
 * LWJGL is initialised later, on a dedicated render thread inside ViewportPanel,
 * so there is no conflict between JavaFX's main-thread requirement and GLFW's
 * context-creation requirements on Windows.
 */
public final class Main {

    public static void main(String[] args) {
        // JavaFX 17 no longer requires the module flag when launched via
        // the Gradle application plugin with proper JVM args in build.gradle.kts
        Application.launch(MainWindow.class, args);
    }
}
