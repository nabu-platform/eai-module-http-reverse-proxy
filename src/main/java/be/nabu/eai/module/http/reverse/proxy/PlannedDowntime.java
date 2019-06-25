package be.nabu.eai.module.http.reverse.proxy;

import java.io.Serializable;
import java.util.Date;

public class PlannedDowntime implements Serializable {
	private static final long serialVersionUID = 1L;
	private String title, description, subscript;
	private Date from, until;
	
	public String getTitle() {
		return title;
	}
	public void setTitle(String title) {
		this.title = title;
	}
	public Date getFrom() {
		return from;
	}
	public void setFrom(Date from) {
		this.from = from;
	}
	public Date getUntil() {
		return until;
	}
	public void setUntil(Date until) {
		this.until = until;
	}
	public String getDescription() {
		return description;
	}
	public void setDescription(String description) {
		this.description = description;
	}
	public String getSubscript() {
		return subscript;
	}
	public void setSubscript(String subscript) {
		this.subscript = subscript;
	}
}
