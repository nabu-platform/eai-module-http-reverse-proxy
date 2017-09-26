package be.nabu.eai.module.http.reverse.proxy;

import be.nabu.eai.repository.api.Repository;
import be.nabu.eai.repository.managers.base.JAXBArtifactManager;
import be.nabu.libs.resources.api.ResourceContainer;

public class ReverseProxyManager extends JAXBArtifactManager<ReverseProxyConfiguration, ReverseProxy> {

	public ReverseProxyManager() {
		super(ReverseProxy.class);
	}

	@Override
	protected ReverseProxy newInstance(String id, ResourceContainer<?> container, Repository repository) {
		return new ReverseProxy(id, container, repository);
	}

}
