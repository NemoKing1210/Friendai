package dev.friendai.debug;

/** Which part of the log tree one writer owns. A writer never touches the other scope's files. */
public enum LogWriterScope {
    SERVER,
    CLIENT
}
