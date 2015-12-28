package org.jenkinsci.plugins.ParameterizedRemoteTrigger;

public class RemoteBuildException extends Exception {
    public RemoteBuildException() {
    }

    public RemoteBuildException(String message) {
        super(message);
    }

    public RemoteBuildException(String message, Throwable cause) {
        super(message, cause);
    }

    public RemoteBuildException(Throwable cause) {
        super(cause);
    }
}
