package dev.mcdocker;

import java.util.List;

/** Immutable snapshot of a container. Two equal snapshots => no need to redraw. */
public record ContainerInfo(
        String id,
        String name,
        String state,
        String image,
        List<String> ports,
        List<String> volumes) {

    public String shortId() {
        return id.length() > 12 ? id.substring(0, 12) : id;
    }
}
