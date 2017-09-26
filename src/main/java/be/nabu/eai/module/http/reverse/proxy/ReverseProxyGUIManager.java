package be.nabu.eai.module.http.reverse.proxy;

import java.io.IOException;

import be.nabu.eai.developer.MainController;
import be.nabu.eai.developer.managers.base.BaseJAXBComplexGUIManager;
import be.nabu.eai.repository.resources.RepositoryEntry;
import be.nabu.libs.property.api.Value;

public class ReverseProxyGUIManager extends BaseJAXBComplexGUIManager<ReverseProxyConfiguration, ReverseProxy> {

	public ReverseProxyGUIManager() {
		super("Reverse Proxy", ReverseProxy.class, new ReverseProxyManager(), ReverseProxyConfiguration.class);
	}

	@Override
	protected ReverseProxy newInstance(MainController controller, RepositoryEntry entry, Value<?>...values) throws IOException {
		return new ReverseProxy(entry.getId(), entry.getContainer(), entry.getRepository());
	}

	@Override
	public String getCategory() {
		return "Protocols";
	}

}
