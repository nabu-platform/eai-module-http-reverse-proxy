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
