package com.erkasa.backend.features.promptvideo.script;

/**
 * One message of an "exchange" scene (9.4b): party {@code from} sends {@code label} to party
 * {@code to}. Parties are numbered from 0 in the order of the visual's items.
 *
 * @param from  sender, 0, 1 or 2
 * @param to    receiver, 0, 1 or 2 (not the sender)
 * @param label what travels, at most 5 words, e.g. "where is example.com?" or "200 OK"
 */
public record Message(int from, int to, String label) {
}
