package org.subsound.integration.platform;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Custom DNS resolver that caps resolution time at 5 seconds.
 * macOS native DNS resolution can take ~30 seconds to fail when offline.
 * Registered via META-INF/services/java.net.spi.InetAddressResolverProvider.
 */
public class TimeoutDnsResolverProvider extends InetAddressResolverProvider {
    private static final Duration DNS_TIMEOUT = Duration.ofSeconds(5);

    @Override
    public InetAddressResolver get(Configuration configuration) {
        // Get the built-in resolver to delegate to
        var builtinResolver = configuration.builtinResolver();
        return new TimeoutDnsResolver(builtinResolver);
    }

    @Override
    public String name() {
        return "Subsound Timeout DNS Resolver";
    }

    private static class TimeoutDnsResolver implements InetAddressResolver {
        private final InetAddressResolver delegate;

        TimeoutDnsResolver(InetAddressResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
            try {
                var result = CompletableFuture.supplyAsync(() -> {
                    try {
                        return delegate.lookupByName(host, lookupPolicy).toList();
                    } catch (UnknownHostException e) {
                        throw new RuntimeException(e);
                    }
                }).orTimeout(DNS_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS).get();
                return result.stream();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException re && re.getCause() instanceof UnknownHostException uhe) {
                    throw uhe;
                }
                if (e.getCause() instanceof TimeoutException) {
                    throw new UnknownHostException("DNS lookup timed out after " + DNS_TIMEOUT.toSeconds() + "s for " + host);
                }
                throw new UnknownHostException("DNS lookup failed for " + host + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UnknownHostException("DNS lookup interrupted for " + host);
            }
        }

        @Override
        public String lookupByAddress(byte[] addr) throws UnknownHostException {
            return delegate.lookupByAddress(addr);
        }
    }
}
