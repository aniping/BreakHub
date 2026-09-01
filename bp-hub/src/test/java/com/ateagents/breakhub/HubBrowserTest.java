package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

class HubBrowserTest {

    @Test
    void opensWildcardBindingsThroughTheIpv4LoopbackAddress() {
        URI browserUri = HubBrowser.uri("0.0.0.0", 18621);

        assertThat(browserUri).isEqualTo(URI.create("http://127.0.0.1:18621/"));
    }

    @Test
    void preservesAnExplicitServerAddress() {
        URI browserUri = HubBrowser.uri("192.168.1.20", 18621);

        assertThat(browserUri).isEqualTo(URI.create("http://192.168.1.20:18621/"));
    }
}
