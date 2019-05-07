package be.nabu.eai.module.http.reverse.proxy;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.eai.module.http.reverse.proxy.ReverseProxyConfiguration.ReverseProxyEntry;
import be.nabu.eai.repository.EAIRepositoryUtils;
import be.nabu.eai.repository.RepositoryThreadFactory;
import be.nabu.eai.repository.api.Repository;
import be.nabu.eai.repository.api.cluster.Cluster;
import be.nabu.eai.repository.artifacts.jaxb.JAXBArtifact;
import be.nabu.libs.artifacts.api.StartableArtifact;
import be.nabu.libs.artifacts.api.StoppableArtifact;
import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.events.api.EventSubscription;
import be.nabu.libs.events.impl.EventDispatcherImpl;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.client.nio.NIOHTTPClientImpl;
import be.nabu.libs.http.core.CustomCookieStore;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.libs.http.server.nio.MemoryMessageDataProvider;
import be.nabu.libs.nio.PipelineUtils;
import be.nabu.libs.nio.api.Pipeline;
import be.nabu.libs.nio.api.events.ConnectionEvent;
import be.nabu.libs.nio.api.events.ConnectionEvent.ConnectionState;
import be.nabu.libs.nio.impl.MessagePipelineImpl;
import be.nabu.libs.nio.impl.NIOFixedConnector;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.libs.services.api.ServiceException;
import be.nabu.libs.validator.api.ValidationMessage.Severity;
import be.nabu.utils.cep.api.EventSeverity;
import be.nabu.utils.cep.impl.HTTPComplexEventImpl;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.mime.api.ContentPart;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;

public class ReverseProxy extends JAXBArtifact<ReverseProxyConfiguration> implements StartableArtifact, StoppableArtifact {

	private static final String REVERSE_PROXY_CLIENT = "reverseProxyClient";
	private Map<String, String> hostMapping = new HashMap<String, String>();
	private Map<Cluster, Integer> roundRobin = new HashMap<Cluster, Integer>();
	private List<EventSubscription<?, ?>> subscriptions = new ArrayList<EventSubscription<?, ?>>();
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	public ReverseProxy(String id, ResourceContainer<?> directory, Repository repository) {
		super(id, directory, repository, "reverse-proxy.xml", ReverseProxyConfiguration.class);
	}

	@Override
	public void stop() throws IOException {
		for (EventSubscription<?, ?> subscription : subscriptions) {
			subscription.unsubscribe();
		}
		subscriptions.clear();
	}

	@Override
	public void start() throws IOException {
		// need to redirect same ip to same server (for optimal cache reusage etc), this also provides more or less sticky sessions if necessary
		// this can prevent the need for jwt-based sessions
		if (getConfig().getEntries() != null) {
			for (final ReverseProxyEntry entry : getConfig().getEntries()) {
				if (entry.getHost() != null && entry.getCluster() != null) {
					
					// make sure we listen to disconnects from the server so we can close outstanding clients
					entry.getHost().getConfig().getServer().getServer().getDispatcher().subscribe(ConnectionEvent.class, new EventHandler<ConnectionEvent, Void>() {
						@Override
						public Void handle(ConnectionEvent event) {
//							logger.info("[" + event.getState() + "] --SERVER-- event: " + event.getPipeline() + " from " + event.getPipeline().getSourceContext().getSocketAddress() + " -> " + (event.getPipeline().getContext().get(REVERSE_PROXY_CLIENT) != null));
							if (event.getState() == ConnectionState.CLOSED) {
								NIOHTTPClientImpl client = (NIOHTTPClientImpl) event.getPipeline().getContext().get(REVERSE_PROXY_CLIENT);
								if (client != null) {
									try {
										client.close();
									}
									catch (IOException e) {
										logger.warn("Could not close http client", e);
									}
									event.getPipeline().getContext().remove(REVERSE_PROXY_CLIENT);
								}
							}
							return null;
						}
					});
					
					final EventDispatcher dispatcher = getRepository().getComplexEventDispatcher();
					
					EventSubscription<HTTPRequest, HTTPResponse> subscription = entry.getHost().getDispatcher().subscribe(HTTPRequest.class, new EventHandler<HTTPRequest, HTTPResponse>() {
						@Override
						public HTTPResponse handle(HTTPRequest event) {
							return handle(entry, event, 0);
						}

						private HTTPResponse handle(final ReverseProxyEntry entry, HTTPRequest event, int attempt) {
							final Pipeline pipeline = PipelineUtils.getPipeline();

							HTTPComplexEventImpl request = null;
							String remoteHost = null;
							
							NIOHTTPClientImpl client = (NIOHTTPClientImpl) pipeline.getContext().get(REVERSE_PROXY_CLIENT);
							if (client == null) {
								if (remoteHost == null) {
									InetSocketAddress socketAddress = (InetSocketAddress) pipeline.getSourceContext().getSocketAddress();
									remoteHost = socketAddress.getHostString();
								}
								if (!hostMapping.containsKey(remoteHost)) {
									synchronized(hostMapping) {
										if (!hostMapping.containsKey(remoteHost)) {
											int robin = roundRobin.containsKey(entry.getCluster()) ? roundRobin.get(entry.getCluster()) : -1;
											robin++;
											if (robin >= entry.getCluster().getMembers().size()) {
												robin = 0;
											}
											hostMapping.put(remoteHost, entry.getCluster().getMembers().get(robin).getAddress().getHostString() + ":" + entry.getCluster().getMembers().get(robin).getAddress().getPort());
											roundRobin.put(entry.getCluster(), robin);
										}
									}
								}
								client = new NIOHTTPClientImpl(null, 3, 1, 1, 
									new EventDispatcherImpl(), 
									new MemoryMessageDataProvider(), 
									new CookieManager(new CustomCookieStore(), CookiePolicy.ACCEPT_NONE), 
									new RepositoryThreadFactory(getRepository()));
								pipeline.getContext().put(REVERSE_PROXY_CLIENT, client);

								final NIOHTTPClientImpl finalC = client;
								client.getDispatcher().subscribe(ConnectionEvent.class, new EventHandler<ConnectionEvent, Void>() {
									@Override
									public Void handle(ConnectionEvent event) {
//										logger.info("[" + event.getState() + "] --CLIENT-- event: " + ((MessagePipelineImpl) event.getPipeline()).getSelectionKey().channel() + " -> " + ((MessagePipelineImpl) event.getPipeline()).getSelectionKey().channel().hashCode() + " FROM " + finalC.getNIOClient().hashCode());
										return null;
									}
								});

								client.setAmountOfRetries(2);
								
								String host = hostMapping.get(remoteHost);
								int indexOf = host.indexOf(':');
								int port = 80;
								if (indexOf > 0) {
									port = Integer.parseInt(host.substring(indexOf + 1));
									host = host.substring(0, indexOf);
								}
								client.getNIOClient().setConnector(new NIOFixedConnector(host, port));
							}
							
							if (dispatcher != null) {
								request = new HTTPComplexEventImpl();
								request.setArtifactId(getId());
								request.setEventName("reverse-proxy-request");
								request.setEventCategory("reverse-proxy");
								request.setEventCount(attempt + 1);
								request.setMethod(event.getMethod());
								Header header = MimeUtils.getHeader("User-Agent", event.getContent().getHeaders());
								if (header != null) {
									request.setUserAgent(MimeUtils.getFullHeaderValue(header));
								}
								
								InetSocketAddress socketAddress = (InetSocketAddress) pipeline.getSourceContext().getSocketAddress();
								if (remoteHost == null) {
									remoteHost = socketAddress.getHostString();
								}
								request.setSourceHost(socketAddress.getHostName());
								request.setSourceIp(socketAddress.getAddress().getHostAddress());
								request.setSourcePort(socketAddress.getPort());
								if (socketAddress.getAddress() instanceof Inet6Address) {
									request.setNetworkProtocol("ipv6");
								}
								else {
									request.setNetworkProtocol("ipv4");
								}
								
								String host = hostMapping.get(remoteHost);
								int indexOf = host.indexOf(':');
								Integer port = null;
								if (indexOf > 0) {
									port = Integer.parseInt(host.substring(indexOf + 1));
									host = host.substring(0, indexOf);
								}
								request.setDestinationHost(host);
								request.setDestinationPort(port);
								request.setTransportProtocol("TCP");
								request.setApplicationProtocol(entry.getHost().getConfig().getServer().isSecure() ? "HTTPS" : "HTTP");
								request.setSizeIn(MimeUtils.getContentLength(event.getContent().getHeaders()));
								request.setStarted(new Date());
							}
							try {
								Future<HTTPResponse> call = client.call(event, false);
								long timeout = getConfig().getTimeout() != null ? getConfig().getTimeout() : 30 * 60000;
								HTTPResponse httpResponse = call.get(timeout, TimeUnit.MILLISECONDS);
								
								if (request != null) {
									request.setRequestUri(HTTPUtils.getURI(event, entry.getHost().getConfig().getServer().isSecure()));
									request.setStopped(new Date());
									request.setDuration(request.getStopped().getTime() - request.getStarted().getTime());
									request.setSeverity(httpResponse.getCode() >= 403 ? EventSeverity.WARNING : EventSeverity.INFO);
									request.setResponseCode(httpResponse.getCode());
									dispatcher.fire(request, ReverseProxy.this);
								}
								// remove internal headers
								for (ServerHeader header : ServerHeader.values()) {
									httpResponse.getContent().removeHeader(header.getName());
								}
								Header transferEncoding = MimeUtils.getHeader("Transfer-Encoding", httpResponse.getContent().getHeaders());
								// make sure we remove the content-length header if chunked is set
								if (transferEncoding != null && transferEncoding.getValue().equalsIgnoreCase("chunked")) {
									httpResponse.getContent().removeHeader("Content-Length");
								}
								return httpResponse;
							}
							catch (Exception e) {
								if (request != null) {
									request.setStopped(new Date());
									request.setDuration(request.getStopped().getTime() - request.getStarted().getTime());
									request.setSeverity(EventSeverity.ERROR);
									EAIRepositoryUtils.enrich(request, e);
									dispatcher.fire(request, ReverseProxy.this);
								}
								try {
									client.close();
								}
								catch (IOException e1) {
									logger.warn("Could not close client", e1);
								}
								pipeline.getContext().remove(REVERSE_PROXY_CLIENT);
								if (attempt < 2) {
									if (remoteHost == null) {
										InetSocketAddress socketAddress = (InetSocketAddress) pipeline.getSourceContext().getSocketAddress();
										remoteHost = socketAddress.getHostString();
									}
									synchronized(hostMapping) {
										hostMapping.remove(remoteHost);
									}
									return handle(entry, event, attempt + 1);
								}
								else {
									throw new HTTPException(500, e);
								}
							}
						}
					});
					subscriptions.add(subscription);
				}
			}
		}
	}
	
	@Override
	public boolean isStarted() {
		return !subscriptions.isEmpty();
	}
	
}
