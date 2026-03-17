package com.modelviewer.model;

/**
 * Strategy interface for decoding raw OSRS/RSPS model bytes into a {@link ModelMesh}.
 *
 * <p>Multiple implementations exist for different cache format variants.
 * The {@link ModelDecoder} pipeline tries each in registration order,
 * returning the first successful decode.  This allows transparent
 * RSPS/OSRS compatibility without callers needing to know the format.</p>
 *
 * <p>Implementations must be stateless — {@link #decode} may be called
 * concurrently from different threads.</p>
 */
public interface IModelDecoder {

    /**
     * Returns {@code true} if this decoder believes it can handle the given raw
     * model bytes.  Called before {@link #decode} to select the right implementation.
     * <p>Must be fast (typically just a footer byte-check) and must not throw.</p>
     */
    boolean supports(byte[] data);

    /**
     * Decodes {@code data} into a {@link ModelMesh}.
     *
     * @param modelId archive ID used for log messages and GPU LRU keying
     * @param data    raw decompressed model bytes
     * @return decoded mesh, or {@code null} if decoding fails
     */
    ModelMesh decode(int modelId, byte[] data);
}
