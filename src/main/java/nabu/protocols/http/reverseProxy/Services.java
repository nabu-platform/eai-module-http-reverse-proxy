/*
* Copyright (C) 2017 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

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
