package dev.agentconfig.workbench.localweb;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;

/** Cross-platform Java desktop chooser used by the local alpha launcher. */
final class SwingWorkspacePicker implements WorkspacePicker {
    @Override
    public boolean available() {
        return !GraphicsEnvironment.isHeadless();
    }

    @Override
    public Result pick() {
        if (!available()) return Result.unavailable();
        AtomicReference<Result> result = new AtomicReference<>(Result.unavailable());
        Runnable choose = () -> result.set(showChooser());
        try {
            if (SwingUtilities.isEventDispatchThread()) choose.run();
            else SwingUtilities.invokeAndWait(choose);
            return result.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.unavailable();
        } catch (InvocationTargetException | RuntimeException exception) {
            return Result.unavailable();
        }
    }

    private static Result showChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择包含 Skills 的项目文件夹");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
            return Result.cancelled();
        }
        File selected = chooser.getSelectedFile();
        return selected == null ? Result.cancelled() : Result.selected(selected.toPath());
    }
}
