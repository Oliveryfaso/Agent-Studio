package dev.agentconfig.workbench.localweb;

import java.nio.file.Path;

/** Desktop directory chooser boundary; tests inject a picker without opening a window. */
public interface WorkspacePicker {
    boolean available();

    Result pick();

    enum Status {
        SELECTED,
        CANCELLED,
        UNAVAILABLE
    }

    record Result(Status status, Path selectedDirectory) {
        public Result {
            if (status == null) throw new IllegalArgumentException("status");
            if ((status == Status.SELECTED) != (selectedDirectory != null)) {
                throw new IllegalArgumentException("selectedDirectory");
            }
            if (selectedDirectory != null) {
                selectedDirectory = selectedDirectory.toAbsolutePath().normalize();
            }
        }

        public static Result selected(Path directory) {
            return new Result(Status.SELECTED, directory);
        }

        public static Result cancelled() {
            return new Result(Status.CANCELLED, null);
        }

        public static Result unavailable() {
            return new Result(Status.UNAVAILABLE, null);
        }
    }
}
