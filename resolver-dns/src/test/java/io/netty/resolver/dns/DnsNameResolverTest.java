/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.resolver.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.ThreadLocalRandom;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.apache.directory.server.dns.DnsServer;
import org.apache.directory.server.dns.io.encoder.DnsMessageEncoder;
import org.apache.directory.server.dns.io.encoder.ResourceRecordEncoder;
import org.apache.directory.server.dns.messages.DnsMessage;
import org.apache.directory.server.dns.messages.QuestionRecord;
import org.apache.directory.server.dns.messages.RecordClass;
import org.apache.directory.server.dns.messages.RecordType;
import org.apache.directory.server.dns.messages.ResourceRecord;
import org.apache.directory.server.dns.messages.ResourceRecordModifier;
import org.apache.directory.server.dns.protocol.DnsProtocolHandler;
import org.apache.directory.server.dns.protocol.DnsUdpDecoder;
import org.apache.directory.server.dns.protocol.DnsUdpEncoder;
import org.apache.directory.server.dns.store.DnsAttribute;
import org.apache.directory.server.dns.store.RecordStore;
import org.apache.directory.server.protocol.shared.transport.UdpTransport;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolEncoder;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;
import org.apache.mina.transport.socket.DatagramAcceptor;
import org.apache.mina.transport.socket.DatagramSessionConfig;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class DnsNameResolverTest {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DnsNameResolver.class);

    // Using the top-100 web sites ranked in Alexa.com (Oct 2014)
    // Please use the following series of shell commands to get this up-to-date:
    // $ curl -O http://s3.amazonaws.com/alexa-static/top-1m.csv.zip
    // $ unzip -o top-1m.csv.zip top-1m.csv
    // $ head -100 top-1m.csv | cut -d, -f2 | cut -d/ -f1 | while read L; do echo '"'"$L"'",'; done > topsites.txt
    private static final Set<String> DOMAINS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "google.com",
            "facebook.com",
            "youtube.com",
            "yahoo.com",
            "baidu.com",
            "wikipedia.org",
            "amazon.com",
            "twitter.com",
            "qq.com",
            "taobao.com",
            "linkedin.com",
            "google.co.in",
            "live.com",
            "hao123.com",
            "sina.com.cn",
            "blogspot.com",
            "weibo.com",
            "yahoo.co.jp",
            "tmall.com",
            "yandex.ru",
            "sohu.com",
            "bing.com",
            "ebay.com",
            "pinterest.com",
            "vk.com",
            "google.de",
            "wordpress.com",
            "apple.com",
            "google.co.jp",
            "google.co.uk",
            "360.cn",
            "instagram.com",
            "google.fr",
            "msn.com",
            "ask.com",
            "soso.com",
            "google.com.br",
            "tumblr.com",
            "paypal.com",
            "mail.ru",
            "xvideos.com",
            "microsoft.com",
            "google.ru",
            "reddit.com",
            "google.it",
            "imgur.com",
            "163.com",
            "google.es",
            "imdb.com",
            "aliexpress.com",
            "t.co",
            "go.com",
            "adcash.com",
            "craigslist.org",
            "amazon.co.jp",
            "alibaba.com",
            "google.com.mx",
            "stackoverflow.com",
            "xhamster.com",
            "fc2.com",
            "google.ca",
            "bbc.co.uk",
            "espn.go.com",
            "cnn.com",
            "google.co.id",
            "people.com.cn",
            "gmw.cn",
            "pornhub.com",
            "blogger.com",
            "huffingtonpost.com",
            "flipkart.com",
            "akamaihd.net",
            "google.com.tr",
            "amazon.de",
            "netflix.com",
            "onclickads.net",
            "googleusercontent.com",
            "kickass.to",
            "google.com.au",
            "google.pl",
            "xinhuanet.com",
            "ebay.de",
            "wordpress.org",
            "odnoklassniki.ru",
            "google.com.hk",
            "adobe.com",
            "dailymotion.com",
            "dailymail.co.uk",
            "indiatimes.com",
            "amazon.co.uk",
            "xnxx.com",
            "rakuten.co.jp",
            "dropbox.com",
            "tudou.com",
            "about.com",
            "cnet.com",
            "vimeo.com",
            "redtube.com",
            "blogspot.in")));

    /**
     * The list of the domain names to exclude from {@link #testResolveAorAAAA()}.
     */
    private static final Set<String> EXCLUSIONS_RESOLVE_A = new HashSet<String>();
    static {
        Collections.addAll(
                EXCLUSIONS_RESOLVE_A,
                "akamaihd.net",
                "googleusercontent.com",
                StringUtil.EMPTY_STRING);
    }

    /**
     * The list of the domain names to exclude from {@link #testResolveAAAA()}.
     * Unfortunately, there are only handful of domain names with IPv6 addresses.
     */
    private static final Set<String> EXCLUSIONS_RESOLVE_AAAA = new HashSet<String>();
    static {
        EXCLUSIONS_RESOLVE_AAAA.addAll(EXCLUSIONS_RESOLVE_A);
        EXCLUSIONS_RESOLVE_AAAA.addAll(DOMAINS);
        EXCLUSIONS_RESOLVE_AAAA.removeAll(Arrays.asList(
                "google.com",
                "facebook.com",
                "youtube.com",
                "wikipedia.org",
                "google.co.in",
                "blogspot.com",
                "vk.com",
                "google.de",
                "google.co.jp",
                "google.co.uk",
                "google.fr",
                "google.com.br",
                "google.ru",
                "google.it",
                "google.es",
                "google.com.mx",
                "xhamster.com",
                "google.ca",
                "google.co.id",
                "blogger.com",
                "flipkart.com",
                "google.com.tr",
                "google.com.au",
                "google.pl",
                "google.com.hk",
                "blogspot.in"
        ));
    }

    /**
     * The list of the domain names to exclude from {@link #testQueryMx()}.
     */
    private static final Set<String> EXCLUSIONS_QUERY_MX = new HashSet<String>();
    static {
        Collections.addAll(
                EXCLUSIONS_QUERY_MX,
                "hao123.com",
                "blogspot.com",
                "t.co",
                "espn.go.com",
                "people.com.cn",
                "googleusercontent.com",
                "blogspot.in",
                StringUtil.EMPTY_STRING);
    }

    private static final TestDnsServer dnsServer = new TestDnsServer();
    private static final EventLoopGroup group = new NioEventLoopGroup(1);
    private static DnsNameResolver resolver;

    @BeforeClass
    public static void init() throws Exception {
        dnsServer.start();
        resolver = new DnsNameResolver(group.next(), NioDatagramChannel.class,
                                       DnsServerAddresses.singleton(dnsServer.localAddress()));
        resolver.setMaxQueriesPerResolve(1);
        resolver.setOptResourceEnabled(false);
    }
    @AfterClass
    public static void destroy() {
        dnsServer.stop();
        group.shutdownGracefully();
    }

    @After
    public void reset() throws Exception {
        resolver.clearCache();
    }

    @Test
    public void testResolveAorAAAA() throws Exception {
        testResolve0(EXCLUSIONS_RESOLVE_A, InternetProtocolFamily.IPv4, InternetProtocolFamily.IPv6);
    }

    @Test
    public void testResolveAAAAorA() throws Exception {
        testResolve0(EXCLUSIONS_RESOLVE_A, InternetProtocolFamily.IPv6, InternetProtocolFamily.IPv4);
    }

    @Test
    public void  testResolveA() throws Exception {
        final int oldMinTtl = resolver.minTtl();
        final int oldMaxTtl = resolver.maxTtl();

        // Cache for eternity.
        resolver.setTtl(Integer.MAX_VALUE, Integer.MAX_VALUE);

        try {
            final Map<String, InetAddress> resultA = testResolve0(EXCLUSIONS_RESOLVE_A, InternetProtocolFamily.IPv4);

            // Now, try to resolve again to see if it's cached.
            // This test works because the DNS servers usually randomizes the order of the records in a response.
            // If cached, the resolved addresses must be always same, because we reuse the same response.

            final Map<String, InetAddress> resultB = testResolve0(EXCLUSIONS_RESOLVE_A, InternetProtocolFamily.IPv4);

            // Ensure the result from the cache is identical from the uncached one.
            assertThat(resultB.size(), is(resultA.size()));
            for (Entry<String, InetAddress> e: resultA.entrySet()) {
                InetAddress expected = e.getValue();
                InetAddress actual = resultB.get(e.getKey());
                if (!actual.equals(expected)) {
                    // Print the content of the cache when test failure is expected.
                    System.err.println("Cache for " + e.getKey() + ": " + resolver.resolveAll(e.getKey(), 0).getNow());
                }
                assertThat(actual, is(expected));
            }
        } finally {
            // Restore the TTL configuration.
            resolver.setTtl(oldMinTtl, oldMaxTtl);
        }
    }

    @Test
    public void testResolveAAAA() throws Exception {
        testResolve0(EXCLUSIONS_RESOLVE_AAAA, InternetProtocolFamily.IPv6);
    }

    private static Map<String, InetAddress> testResolve0(
            Set<String> excludedDomains, InternetProtocolFamily... famililies) throws InterruptedException {

        final List<InternetProtocolFamily> oldResolveAddressTypes = resolver.resolveAddressTypes();

        assertThat(resolver.isRecursionDesired(), is(true));
        assertThat(oldResolveAddressTypes.size(), is(InternetProtocolFamily.values().length));

        resolver.setResolveAddressTypes(famililies);

        final Map<String, InetAddress> results = new HashMap<String, InetAddress>();
        try {
            final Map<InetSocketAddress, Future<InetSocketAddress>> futures =
                    new LinkedHashMap<InetSocketAddress, Future<InetSocketAddress>>();

            for (String name : DOMAINS) {
                if (excludedDomains.contains(name)) {
                    continue;
                }

                resolve(futures, name);
            }

            for (Entry<InetSocketAddress, Future<InetSocketAddress>> e : futures.entrySet()) {
                InetSocketAddress unresolved = e.getKey();
                InetSocketAddress resolved = e.getValue().sync().getNow();

                logger.info("{}: {}", unresolved.getHostString(), resolved.getAddress().getHostAddress());

                assertThat(resolved.isUnresolved(), is(false));
                assertThat(resolved.getHostString(), is(unresolved.getHostString()));
                assertThat(resolved.getPort(), is(unresolved.getPort()));

                boolean typeMatches = false;
                for (InternetProtocolFamily f: famililies) {
                    Class<?> resolvedType = resolved.getAddress().getClass();
                    if (f.addressType().isAssignableFrom(resolvedType)) {
                        typeMatches = true;
                    }
                }

                assertThat(typeMatches, is(true));

                results.put(resolved.getHostString(), resolved.getAddress());
            }
        } finally {
            resolver.setResolveAddressTypes(oldResolveAddressTypes);
        }

        return results;
    }

    @Test
    public void testQueryMx() throws Exception {
        assertThat(resolver.isRecursionDesired(), is(true));

        Map<String, Future<AddressedEnvelope<DnsResponse, InetSocketAddress>>> futures =
                new LinkedHashMap<String, Future<AddressedEnvelope<DnsResponse, InetSocketAddress>>>();
        for (String name: DOMAINS) {
            if (EXCLUSIONS_QUERY_MX.contains(name)) {
                continue;
            }

            queryMx(futures, name);
        }

        for (Entry<String, Future<AddressedEnvelope<DnsResponse, InetSocketAddress>>> e: futures.entrySet()) {
            String hostname = e.getKey();
            Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> f = e.getValue().awaitUninterruptibly();

            DnsResponse response = f.getNow().content();
            assertThat(response.code(), is(DnsResponseCode.NOERROR));

            final int answerCount = response.count(DnsSection.ANSWER);
            final List<DnsRecord> mxList = new ArrayList<DnsRecord>(answerCount);
            for (int i = 0; i < answerCount; i ++) {
                final DnsRecord r = response.recordAt(DnsSection.ANSWER, i);
                if (r.type() == DnsRecordType.MX) {
                    mxList.add(r);
                }
            }

            assertThat(mxList.size(), is(greaterThan(0)));
            StringBuilder buf = new StringBuilder();
            for (DnsRecord r: mxList) {
                ByteBuf recordContent = ((ByteBufHolder) r).content();

                buf.append(StringUtil.NEWLINE);
                buf.append('\t');
                buf.append(r.name());
                buf.append(' ');
                buf.append(r.type().name());
                buf.append(' ');
                buf.append(recordContent.readUnsignedShort());
                buf.append(' ');
                buf.append(DnsNameResolverContext.decodeDomainName(recordContent));
            }

            logger.info("{} has the following MX records:{}", hostname, buf);
            response.release();
        }
    }

    @Test
    public void testNegativeTtl() throws Exception {
        final int oldNegativeTtl = resolver.negativeTtl();
        resolver.setNegativeTtl(10);
        try {
            resolveNonExistentDomain();

            final int size = 10000;
            final List<UnknownHostException> exceptions = new ArrayList<UnknownHostException>();

            // If negative cache works, this thread should be done really quickly.
            final Thread negativeLookupThread = new Thread() {
                @Override
                public void run() {
                    for (int i = 0; i < size; i++) {
                        exceptions.add(resolveNonExistentDomain());
                        if (isInterrupted()) {
                            break;
                        }
                    }
                }
            };

            negativeLookupThread.start();
            negativeLookupThread.join(5000);

            if (negativeLookupThread.isAlive()) {
                negativeLookupThread.interrupt();
                fail("Cached negative lookups did not finish quickly.");
            }

            assertThat(exceptions, hasSize(size));
        } finally {
            resolver.setNegativeTtl(oldNegativeTtl);
        }
    }

    private static UnknownHostException resolveNonExistentDomain() {
        try {
            resolver.resolve("non-existent.netty.io", 0).sync();
            fail();
            return null;
        } catch (Exception e) {
            assertThat(e, is(instanceOf(UnknownHostException.class)));
            return (UnknownHostException) e;
        }
    }

    @Test
    public void testResolveIp() {
        InetSocketAddress unresolved =
                InetSocketAddress.createUnresolved("10.0.0.1", ThreadLocalRandom.current().nextInt(65536));

        InetSocketAddress address = resolver.resolve(unresolved).syncUninterruptibly().getNow();

        assertEquals("10.0.0.1", address.getHostName());
    }

    private static void resolve(
            Map<InetSocketAddress, Future<InetSocketAddress>> futures, String hostname) {
        InetSocketAddress unresolved =
                InetSocketAddress.createUnresolved(hostname, ThreadLocalRandom.current().nextInt(65536));

        futures.put(unresolved, resolver.resolve(unresolved));
    }

    private static void queryMx(
            Map<String, Future<AddressedEnvelope<DnsResponse, InetSocketAddress>>> futures,
            String hostname) throws Exception {
        futures.put(hostname, resolver.query(new DefaultDnsQuestion(hostname, DnsRecordType.MX)));
    }

    private static final class TestDnsServer extends DnsServer {

        @Override
        public void start() throws IOException {
            InetSocketAddress address = new InetSocketAddress(NetUtil.LOCALHOST4, 0);
            UdpTransport transport = new UdpTransport(address.getHostName(), address.getPort());
            setTransports(transport);

            DatagramAcceptor acceptor = transport.getAcceptor();

            acceptor.setHandler(new DnsProtocolHandler(this, new TestRecordStore()) {
                @Override
                public void sessionCreated(IoSession session) throws Exception {
                    // USe our own codec to support AAAA testing
                    session.getFilterChain()
                           .addFirst("codec", new ProtocolCodecFilter(new TestDnsProtocolUdpCodecFactory()));
                }
            });

            ((DatagramSessionConfig) acceptor.getSessionConfig()).setReuseAddress(true);

            // Start the listener
            acceptor.bind();
        }

        public InetSocketAddress localAddress() {
            return (InetSocketAddress) getTransports()[0].getAcceptor().getLocalAddress();
        }

        /**
         * {@link ProtocolCodecFactory} which allows to test AAAA resolution.
         */
        private static final class TestDnsProtocolUdpCodecFactory implements ProtocolCodecFactory {
            private final DnsMessageEncoder encoder = new DnsMessageEncoder();
            private final TestAAAARecordEncoder recordEncoder = new TestAAAARecordEncoder();

            @Override
            public ProtocolEncoder getEncoder(IoSession session) throws Exception {
                return new DnsUdpEncoder() {

                    @Override
                    public void encode(IoSession session, Object message, ProtocolEncoderOutput out) {
                        IoBuffer buf = IoBuffer.allocate(1024);
                        DnsMessage dnsMessage = (DnsMessage) message;
                        encoder.encode(buf, dnsMessage);
                        for (ResourceRecord record: dnsMessage.getAnswerRecords()) {
                            // This is a hack to allow to also test for AAAA resolution as DnsMessageEncoder
                            // does not support it and it is hard to extend, because the interesting methods
                            // are private...
                            // In case of RecordType.AAAA we need to encode the RecordType by ourself.
                            if (record.getRecordType() == RecordType.AAAA) {
                                try {
                                    recordEncoder.put(buf, record);
                                } catch (IOException e) {
                                    // Should never happen
                                    throw new IllegalStateException(e);
                                }
                            }
                        }
                        buf.flip();

                        out.write(buf);
                    }
                };
            }

            @Override
            public ProtocolDecoder getDecoder(IoSession session) throws Exception {
                return new DnsUdpDecoder();
            }

            private static final class TestAAAARecordEncoder extends ResourceRecordEncoder {
                private static final byte[] ADDRESS_BYTES = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
                @Override
                protected void putResourceRecordData(IoBuffer ioBuffer, ResourceRecord resourceRecord) {
                    if (!"::1".equals(resourceRecord.get(DnsAttribute.IP_ADDRESS))) {
                        throw new IllegalStateException("Only supposed to be used with IPV6 address of ::1");
                    }
                    // encode the ::1
                    ioBuffer.put(ADDRESS_BYTES);
                }
            }
        }

        private static final class TestRecordStore implements RecordStore {
            private static final int[] numbers = new int[254];
            private static final char[] chars = new char[26];

            static {
                for (int i = 0; i < numbers.length; i++) {
                    numbers[i] = i + 1;
                }

                for (int i = 0; i < chars.length; i++) {
                    chars[i] =  (char) ('a' + i);
                }
            }

            private static int index(int arrayLength) {
                return Math.abs(ThreadLocalRandom.current().nextInt()) % arrayLength;
            }

            private static String nextDomain() {
               return chars[index(chars.length)] + ".netty.io";
            }

            private static String nextIp() {
                return ippart() + "." + ippart() + '.' + ippart() + '.' + ippart();
            }

            private static int ippart() {
                return numbers[index(numbers.length)];
            }

            @Override
            public Set<ResourceRecord> getRecords(QuestionRecord questionRecord) {
                String name = questionRecord.getDomainName();
                if (DOMAINS.contains(name)) {
                    ResourceRecordModifier rm = new ResourceRecordModifier();
                    rm.setDnsClass(RecordClass.IN);
                    rm.setDnsName(name);
                    rm.setDnsTtl(100);
                    rm.setDnsType(questionRecord.getRecordType());

                    switch (questionRecord.getRecordType()) {
                    case A:
                        do {
                            String ip = nextIp();
                            rm.put(DnsAttribute.IP_ADDRESS, ip);
                        } while (ThreadLocalRandom.current().nextBoolean());
                        break;
                    case AAAA:
                        do {
                            rm.put(DnsAttribute.IP_ADDRESS, "::1");
                        } while (ThreadLocalRandom.current().nextBoolean());
                        break;
                    case MX:
                        int prioritity = 0;
                        do {
                            rm.put(DnsAttribute.DOMAIN_NAME, nextDomain());
                            rm.put(DnsAttribute.MX_PREFERENCE, String.valueOf(++prioritity));
                        } while (ThreadLocalRandom.current().nextBoolean());
                        break;
                    default:
                        return null;
                    }
                    return Collections.singleton(rm.getEntry());
                }
                return null;
            }
        }
    }
}
