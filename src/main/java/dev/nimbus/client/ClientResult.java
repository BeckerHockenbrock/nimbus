package dev.nimbus.client;

/** A proxy response and its cache metadata. */
public record ClientResult<T>(T value, String cache, String elapsedMs) { }
