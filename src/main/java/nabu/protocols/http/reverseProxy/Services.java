package nabu.protocols.http.reverseProxy;

import javax.jws.WebParam;
import javax.jws.WebService;
import javax.validation.constraints.NotNull;

import be.nabu.eai.module.http.reverse.proxy.PlannedDowntime;
import be.nabu.eai.module.http.reverse.proxy.ReverseProxy;
import be.nabu.eai.repository.EAIResourceRepository;
import be.nabu.libs.artifacts.api.Artifact;

@WebService
public class Services {
	public void down(@NotNull @WebParam(name = "reverseProxyId") String reverseProxyId, @WebParam(name = "plan") PlannedDowntime plan) {
		Artifact resolve = EAIResourceRepository.getInstance().resolve(reverseProxyId);
		if (!(resolve instanceof ReverseProxy)) {
			throw new IllegalArgumentException("Could not find reverse proxy: " + reverseProxyId);
		}
		((ReverseProxy) resolve).setPlanned(plan == null ? new PlannedDowntime() : plan);
	}
	public void up(@NotNull @WebParam(name = "reverseProxyId") String reverseProxyId) {
		Artifact resolve = EAIResourceRepository.getInstance().resolve(reverseProxyId);
		if (!(resolve instanceof ReverseProxy)) {
			throw new IllegalArgumentException("Could not find reverse proxy: " + reverseProxyId);
		}
		((ReverseProxy) resolve).setPlanned(null);
	}
}
