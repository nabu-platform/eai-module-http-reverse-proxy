package be.nabu.eai.module.http.reverse.proxy;

import java.net.URI;
import java.util.List;

import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import be.nabu.eai.module.http.virtual.VirtualHostArtifact;
import be.nabu.eai.repository.api.cluster.ClusterArtifact;
import be.nabu.eai.repository.jaxb.ArtifactXMLAdapter;

@XmlRootElement(name = "reverseProxy")
public class ReverseProxyConfiguration {

	private List<ReverseProxyEntry> entries;
	private Long timeout; 
	private Integer ioPoolSize, processPoolSize;
	
	public List<ReverseProxyEntry> getEntries() {
		return entries;
	}
	public void setEntries(List<ReverseProxyEntry> entries) {
		this.entries = entries;
	}

	public Long getTimeout() {
		return timeout;
	}
	public void setTimeout(Long timeout) {
		this.timeout = timeout;
	}

	public Integer getIoPoolSize() {
		return ioPoolSize;
	}
	public void setIoPoolSize(Integer ioPoolSize) {
		this.ioPoolSize = ioPoolSize;
	}
	public Integer getProcessPoolSize() {
		return processPoolSize;
	}
	public void setProcessPoolSize(Integer processPoolSize) {
		this.processPoolSize = processPoolSize;
	}

	public static class ReverseProxyHeartbeat {
		// the logical name of this heartbeat check, it will be included in the event and can be used for event analysis
		private String name;
		// the path on the target server to check
		private String path;
		// the host that should be used
		private String host;
		// the maximum timeout of the call, defaults to 15s
		private Long maxTimeout;
		public String getPath() {
			return path;
		}
		public void setPath(String path) {
			this.path = path;
		}
		public Long getMaxTimeout() {
			return maxTimeout;
		}
		public void setMaxTimeout(Long maxTimeout) {
			this.maxTimeout = maxTimeout;
		}
		public String getName() {
			return name;
		}
		public void setName(String name) {
			this.name = name;
		}
		public String getHost() {
			return host;
		}
		public void setHost(String host) {
			this.host = host;
		}
	}
	
	public static class ReverseProxyEntry {
		private VirtualHostArtifact host;
		private ClusterArtifact cluster;
		private List<DowntimePage> downtimePages;
		private boolean enableWebsockets;
		private String path;
		private List<ReverseProxyHeartbeat> heartbeats;
	
		@NotNull
		@XmlJavaTypeAdapter(value = ArtifactXMLAdapter.class)
		public VirtualHostArtifact getHost() {
			return host;
		}
		public void setHost(VirtualHostArtifact host) {
			this.host = host;
		}
		
		@NotNull
		@XmlJavaTypeAdapter(value = ArtifactXMLAdapter.class)
		public ClusterArtifact getCluster() {
			return cluster;
		}
		public void setCluster(ClusterArtifact cluster) {
			this.cluster = cluster;
		}
		
		public List<DowntimePage> getDowntimePages() {
			return downtimePages;
		}
		public void setDowntimePages(List<DowntimePage> downtimePages) {
			this.downtimePages = downtimePages;
		}
		public boolean isEnableWebsockets() {
			return enableWebsockets;
		}
		public void setEnableWebsockets(boolean enableWebsockets) {
			this.enableWebsockets = enableWebsockets;
		}
		public String getPath() {
			return path;
		}
		public void setPath(String path) {
			this.path = path;
		}
		public List<ReverseProxyHeartbeat> getHeartbeats() {
			return heartbeats;
		}
		public void setHeartbeats(List<ReverseProxyHeartbeat> heartbeats) {
			this.heartbeats = heartbeats;
		}
	}
	
	public static class DowntimePage {
		private URI uri;
		private String language;
		public URI getUri() {
			return uri;
		}
		public void setUri(URI uri) {
			this.uri = uri;
		}
		public String getLanguage() {
			return language;
		}
		public void setLanguage(String language) {
			this.language = language;
		}
	}
}
