package be.nabu.eai.module.http.reverse.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.eai.module.http.reverse.proxy.ReverseProxyConfiguration.DowntimePage;
import be.nabu.eai.module.http.reverse.proxy.ReverseProxyConfiguration.ReverseProxyEntry;
import be.nabu.eai.repository.EAIRepositoryUtils;
import be.nabu.eai.repository.RepositoryThreadFactory;
import be.nabu.eai.repository.api.Repository;
import be.nabu.eai.repository.api.cluster.Cluster;
import be.nabu.eai.repository.api.cluster.ClusterMember;
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
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.libs.http.server.nio.MemoryMessageDataProvider;
import be.nabu.libs.nio.PipelineUtils;
import be.nabu.libs.nio.api.Pipeline;
import be.nabu.libs.nio.api.events.ConnectionEvent;
import be.nabu.libs.nio.api.events.ConnectionEvent.ConnectionState;
import be.nabu.libs.nio.impl.MessagePipelineImpl;
import be.nabu.libs.nio.impl.NIOFixedConnector;
import be.nabu.libs.resources.ResourceFactory;
import be.nabu.libs.resources.ResourceUtils;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.Resource;
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
import be.nabu.utils.mime.api.ModifiablePart;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeContentPart;

public class ReverseProxy extends JAXBArtifact<ReverseProxyConfiguration> implements StartableArtifact, StoppableArtifact {

	private static final String REVERSE_PROXY_CLIENT = "reverseProxyClient";
	private Map<String, String> hostMapping = new HashMap<String, String>();
	private Map<Cluster, Integer> roundRobin = new HashMap<Cluster, Integer>();
	private List<EventSubscription<?, ?>> subscriptions = new ArrayList<EventSubscription<?, ?>>();
	private Logger logger = LoggerFactory.getLogger(getClass());
	private volatile PlannedDowntime planned = null;
	private Map<String, String> downtimeContent = new HashMap<String, String>();
	private ExecutorService ioExecutors, processExecutors;
	private boolean useSharedPools = Boolean.parseBoolean(System.getProperty("reverseProxy.sharePools", "true"));
	
	public ReverseProxy(String id, ResourceContainer<?> directory, Repository repository) {
		super(id, directory, repository, "reverse-proxy.xml", ReverseProxyConfiguration.class);
	}

	@Override
	public void stop() throws IOException {
		if (useSharedPools) {
			if (ioExecutors != null) {
				ioExecutors.shutdown();
			}
			if (processExecutors != null) {
				processExecutors.shutdown();
			}
		}
		for (EventSubscription<?, ?> subscription : subscriptions) {
			subscription.unsubscribe();
		}
		subscriptions.clear();
	}
	
	private ThreadFactory getThreadFactory() {
		final RepositoryThreadFactory threadFactory = new RepositoryThreadFactory(getRepository());
		return new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread thread = threadFactory.newThread(r);
				thread.setDaemon(true);
				return thread;
			}
		};
	}

	@Override
	public void start() throws IOException {
		// need to redirect same ip to same server (for optimal cache reusage etc), this also provides more or less sticky sessions if necessary
		// this can prevent the need for jwt-based sessions
		if (getConfig().getEntries() != null) {
			if (useSharedPools) {
				int ioPoolSize = getConfig().getIoPoolSize() == null ? new Integer(System.getProperty("reverseProxy.ioPoolSize", "25")) : getConfig().getIoPoolSize();
				int processPoolSize = getConfig().getProcessPoolSize() == null ? new Integer(System.getProperty("reverseProxy.processPoolSize", "25")) : getConfig().getProcessPoolSize();
				ioExecutors = Executors.newFixedThreadPool(ioPoolSize, getThreadFactory());
				processExecutors = Executors.newFixedThreadPool(processPoolSize, getThreadFactory());
			}
			for (final ReverseProxyEntry entry : getConfig().getEntries()) {
				if (entry.getHost() != null && entry.getCluster() != null) {
					
					// make sure we listen to disconnects from the server so we can close outstanding clients
					subscriptions.add(entry.getHost().getConfig().getServer().getServer().getDispatcher().subscribe(ConnectionEvent.class, new EventHandler<ConnectionEvent, Void>() {
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
					}));
					
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
								request.setTransportProtocol("TCP");
								request.setApplicationProtocol(entry.getHost().getConfig().getServer().isSecure() ? "HTTPS" : "HTTP");
								request.setSizeIn(MimeUtils.getContentLength(event.getContent().getHeaders()));
								request.setStarted(new Date());
								try {
									request.setRequestUri(HTTPUtils.getURI(event, entry.getHost().getConfig().getServer().isSecure()));
								}
								catch (FormatException e) {
									// ignore
								}
								for (Header header2 : event.getContent().getHeaders()) {
									// user agent is saved in a dedicated field
									if (header2.getName().equalsIgnoreCase("User-Agent")) {
										continue;
									}
									request.getExtensions().put("header:" + header2.getName(), MimeUtils.getFullHeaderValue(header2));
								}
							}
							
							if (getPlanned() != null) {
								return serverDown(event, entry, request);
							}
							
							NIOHTTPClientImpl client = (NIOHTTPClientImpl) pipeline.getContext().get(REVERSE_PROXY_CLIENT);
							// if we don't have a client or it is no longer running, start a new one
							if (client == null || !client.getNIOClient().isStarted()) {
								if (remoteHost == null) {
									InetSocketAddress socketAddress = (InetSocketAddress) pipeline.getSourceContext().getSocketAddress();
									remoteHost = socketAddress.getHostString();
								}
								if (!hostMapping.containsKey(remoteHost)) {
									synchronized(hostMapping) {
										if (!hostMapping.containsKey(remoteHost)) {
											List<ClusterMember> members = entry.getCluster().getMembers();
											if (members.isEmpty()) {
												return serverDown(event, entry, request);
											}
											int robin = roundRobin.containsKey(entry.getCluster()) ? roundRobin.get(entry.getCluster()) : -1;
											robin++;
											if (robin >= members.size()) {
												robin = 0;
											}
											hostMapping.put(remoteHost, members.get(robin).getAddress().getHostString() + ":" + members.get(robin).getAddress().getPort());
											roundRobin.put(entry.getCluster(), robin);
										}
									}
								}
								if (useSharedPools) {
									client = new NIOHTTPClientImpl(null, ioExecutors, processExecutors, 1, 
										new EventDispatcherImpl(), 
										new MemoryMessageDataProvider(), 
										new CookieManager(new CustomCookieStore(), CookiePolicy.ACCEPT_NONE));
								}
								else {
									client = new NIOHTTPClientImpl(null, 3, 1, 1, 
										new EventDispatcherImpl(), 
										new MemoryMessageDataProvider(), 
										new CookieManager(new CustomCookieStore(), CookiePolicy.ACCEPT_NONE), 
										new RepositoryThreadFactory(getRepository()));
								}
								pipeline.getContext().put(REVERSE_PROXY_CLIENT, client);

//								client.getDispatcher().subscribe(ConnectionEvent.class, new EventHandler<ConnectionEvent, Void>() {
//									@Override
//									public Void handle(ConnectionEvent event) {
//										// we can't close the pipeline itself immediately because the answer that triggered the close (e.g. a 500) might still need to be fed back to the client
//										// we can't even check if the pipeline responses are empty because there is a time gap between the last pop and it actually being done
//										// it seems too hard to predict when the offending message is on the pipeline here
//										// note that this does seem to solve the connection timeout problem, so better reenable if we don't find another solution
//										/*if (event.getState() == ConnectionState.CLOSED) {
//											try {
//												MessagePipelineImpl<?, ?> cast = (MessagePipelineImpl<?, ?>) pipeline;
//												// always set it to drain
//												cast.drain();
//											}
//											catch (Exception e) {
//												logger.warn("Could not close server pipeline after client connection failed", e);
//											}
//										}*/
////										logger.info("[" + event.getState() + "] --CLIENT-- event: " + ((MessagePipelineImpl) event.getPipeline()).getSelectionKey().channel() + " -> " + ((MessagePipelineImpl) event.getPipeline()).getSelectionKey().channel().hashCode() + " FROM " + finalC.getNIOClient().hashCode());
//										return null;
//									}
//								});

//								client.setAmountOfRetries(2);
								
								String host = hostMapping.get(remoteHost);
								int indexOf = host.indexOf(':');
								int port = 80;
								if (indexOf > 0) {
									port = Integer.parseInt(host.substring(indexOf + 1));
									host = host.substring(0, indexOf);
								}
								client.getNIOClient().setConnector(new NIOFixedConnector(host, port));
							}
							
							if (request != null) {
								String host = hostMapping.get(remoteHost);
								if (host != null) {
									int indexOf = host.indexOf(':');
									Integer port = null;
									if (indexOf > 0) {
										port = Integer.parseInt(host.substring(indexOf + 1));
										host = host.substring(0, indexOf);
									}
									request.setDestinationHost(host);
									request.setDestinationPort(port);
								}
							}
							
							try {
								Future<HTTPResponse> call = client.call(event, false);
								long timeout = getConfig().getTimeout() != null ? getConfig().getTimeout() : 30 * 60000;
								HTTPResponse httpResponse = call.get(timeout, TimeUnit.MILLISECONDS);
								
								if (request != null) {
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
									return serverDown(event, entry, null);
								}
							}
						}
					});
					subscriptions.add(subscription);
				}
			}
		}
	}
	
	private String getForLanguage(ReverseProxyEntry entry, String language, String cleanedUp) {
		if (entry.getDowntimePages() != null) {
			// browser language
			for (DowntimePage page : entry.getDowntimePages()) {
				if (page != null) {
					if (page.getLanguage() != null && (page.getLanguage().equals(language) || page.getLanguage().equals(cleanedUp))) {
						return readPage(page.getUri());
					}
				}
			}
		}
		return null;
	}

	private String readPage(URI uri) {
		try {
			ReadableContainer<ByteBuffer> readableContainer = ResourceUtils.toReadableContainer(uri, null);
			try {
				return new String(IOUtils.toBytes(readableContainer), "UTF-8");
			}
			finally {
				readableContainer.close();
			}
		}
		catch (Exception e) {
			logger.warn("Could not resolve downtime page", e);
			return null;
		}
	}
	
	private HTTPResponse serverDown(HTTPRequest request, ReverseProxyEntry entry, HTTPComplexEventImpl event) {
		String contentToSend = null;
		List<String> acceptedLanguages = MimeUtils.getAcceptedLanguages(request.getContent().getHeaders());
		for (String acceptedLanguage : acceptedLanguages) {
			String cleanedUp = acceptedLanguage.replaceAll("-.*", "");
			contentToSend = downtimeContent.get(acceptedLanguage);
			if (contentToSend == null) {
				contentToSend = downtimeContent.get(cleanedUp);
			}
			// check if you have a specific downtime page for this language
			if (contentToSend == null) {
				contentToSend = getForLanguage(entry, acceptedLanguage, cleanedUp);
			}
			// we got one from cache, don't restore it
			else {
				break;
			}
			// if so, we store it
			if (contentToSend != null) {
				synchronized(downtimeContent) {
					downtimeContent.put(acceptedLanguage, contentToSend);
					downtimeContent.put(cleanedUp, contentToSend);
				}
				break;
			}
		}
		// nothing for this language, check cache for "no language"
		if (contentToSend == null) {
			contentToSend = downtimeContent.get(null);
		}
		// nothing in the cache, check if there is a fall back page configured
		if (contentToSend == null && entry.getDowntimePages() != null) {
			for (DowntimePage page : entry.getDowntimePages()) {
				if (page.getLanguage() == null && page.getUri() != null) {
					contentToSend = readPage(page.getUri());
					if (contentToSend != null) {
						synchronized (downtimeContent) {
							downtimeContent.put(null, contentToSend);
						}
					}
				}
			}
		}
		// no default fall back page, just get the really default one
		if (contentToSend == null) {
			InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream("reverse-proxy-downtime.html");
			try {
				try {
					contentToSend = new String(IOUtils.toBytes(IOUtils.wrap(stream)), "UTF-8");
					if (contentToSend != null) {
						synchronized (downtimeContent) {
							downtimeContent.put(null, contentToSend);
						}
					}
				}
				finally {
					stream.close();
				}
			}
			catch (Exception e) {
				throw new HTTPException(502, e);
			}
		}
		contentToSend = contentToSend.replace("${title}", planned != null && planned.getTitle() != null ? planned.getTitle() : "Maintenance");
		contentToSend = contentToSend.replace("${description}", planned != null && planned.getDescription() != null ? planned.getDescription() : "We're undergoing a bit of scheduled maintenance.");
		contentToSend = contentToSend.replace("${subscript}", planned != null && planned.getSubscript() != null ? planned.getSubscript() : "Sorry for the inconvenience. We'll be back up and running as fast as possible.");
		byte[] bytes = contentToSend.getBytes(Charset.forName("UTF-8"));
		if (event != null) {
			try {
				event.setRequestUri(HTTPUtils.getURI(request, entry.getHost().getConfig().getServer().isSecure()));
			}
			catch (Exception e) {
				// ignore
			}
			event.setStopped(new Date());
			event.setDuration(event.getStopped().getTime() - event.getStarted().getTime());
			event.setSeverity(EventSeverity.WARNING);
			event.setResponseCode(503);
			getRepository().getComplexEventDispatcher().fire(event, ReverseProxy.this);
		}
		return new DefaultHTTPResponse(request, 503, "Service Unavailable", new PlainMimeContentPart(null, IOUtils.wrap(bytes, true), 
			new MimeHeader("Content-Length", Integer.toString(bytes.length)),
			new MimeHeader("Content-Type", "text/html;charset=UTF-8")));
	}
	
	@Override
	public boolean isStarted() {
		return !subscriptions.isEmpty();
	}

	public PlannedDowntime getPlanned() {
		return planned;
	}
	public void setPlanned(PlannedDowntime planned) {
		this.planned = planned;
	}

}
