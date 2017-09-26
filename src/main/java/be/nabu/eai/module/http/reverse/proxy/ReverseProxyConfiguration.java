package be.nabu.eai.module.http.reverse.proxy;

import java.util.List;

import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import be.nabu.eai.module.cluster.ClusterArtifact;
import be.nabu.eai.module.http.virtual.VirtualHostArtifact;
import be.nabu.eai.repository.jaxb.ArtifactXMLAdapter;

@XmlRootElement(name = "reverseProxy")
public class ReverseProxyConfiguration {

	private List<ReverseProxyEntry> entries;
	private Long timeout; 
	
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

	public static class ReverseProxyEntry {
		private VirtualHostArtifact host;
		private ClusterArtifact cluster;
	
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
	}
}
