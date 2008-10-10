
package net.sourceforge.filebot.web;


import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.swing.Icon;

import net.sourceforge.filebot.ResourceManager;
import net.sourceforge.tuned.XPathUtil;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;


public class TVDotComClient implements EpisodeListClient {
	
	private static final String host = "www.tv.com";
	
	
	@Override
	public String getName() {
		return "TV.com";
	}
	

	@Override
	public Icon getIcon() {
		return ResourceManager.getIcon("search.tvdotcom");
	}
	

	@Override
	public boolean hasSingleSeasonSupport() {
		return true;
	}
	

	@Override
	public List<SearchResult> search(String searchterm) throws IOException, SAXException {
		
		Document dom = HtmlUtil.getHtmlDocument(getSearchUrl(searchterm));
		
		List<Node> nodes = XPathUtil.selectNodes("//H3[@class='title']/A", dom);
		
		List<SearchResult> searchResults = new ArrayList<SearchResult>(nodes.size());
		
		for (Node node : nodes) {
			String title = node.getTextContent();
			String href = XPathUtil.selectString("@href", node);
			
			try {
				URL episodeListingUrl = new URL(href.replaceFirst(Pattern.quote("summary.html?") + ".*", "episode_listings.html"));
				
				searchResults.add(new HyperLink(title, episodeListingUrl));
			} catch (Exception e) {
				Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.WARNING, "Invalid href: " + href, e);
			}
		}
		
		return searchResults;
	}
	

	@Override
	public List<Episode> getEpisodeList(SearchResult searchResult) throws Exception {
		
		// get document for season 1
		Document dom = HtmlUtil.getHtmlDocument(getEpisodeListLink(searchResult, 1));
		
		int seasonCount = XPathUtil.selectInteger("count(id('eps_table')//SELECT[@name='season']/OPTION[text() != 'All Seasons'])", dom);
		
		// we're going to fetch the episode list for each season on multiple threads
		List<Future<List<Episode>>> futures = new ArrayList<Future<List<Episode>>>(seasonCount);
		
		if (seasonCount > 1) {
			// max. 12 threads so we don't get too many concurrent connections
			ExecutorService executor = Executors.newFixedThreadPool(Math.min(seasonCount - 1, 12));
			
			// we already have the document for season 1, start with season 2
			for (int i = 2; i <= seasonCount; i++) {
				futures.add(executor.submit(new GetEpisodeList(searchResult, i)));
			}
			
			// shutdown after all tasks are done
			executor.shutdown();
		}
		
		List<Episode> episodes = new ArrayList<Episode>(25 * seasonCount);
		
		// get episode list from season 1 document
		episodes.addAll(getEpisodeList(searchResult, 1, dom));
		
		// get episodes from executor threads
		for (Future<List<Episode>> future : futures) {
			episodes.addAll(future.get());
		}
		
		return episodes;
	}
	

	@Override
	public List<Episode> getEpisodeList(SearchResult searchResult, int season) throws IOException, SAXException {
		
		Document dom = HtmlUtil.getHtmlDocument(getEpisodeListLink(searchResult, season));
		
		return getEpisodeList(searchResult, season, dom);
	}
	

	private List<Episode> getEpisodeList(SearchResult searchResult, int seasonNumber, Document dom) {
		
		List<Node> nodes = XPathUtil.selectNodes("id('eps_table')//TD[@class='ep_title']/parent::TR", dom);
		
		NumberFormat numberFormat = NumberFormat.getInstance(Locale.ENGLISH);
		numberFormat.setMinimumIntegerDigits(Math.max(Integer.toString(nodes.size()).length(), 2));
		numberFormat.setGroupingUsed(false);
		
		Integer episodeOffset = null;
		
		ArrayList<Episode> episodes = new ArrayList<Episode>(nodes.size());
		
		for (Node node : nodes) {
			String episode = XPathUtil.selectString("./TD[1]", node);
			String title = XPathUtil.selectString("./TD[2]//A", node);
			String season = Integer.toString(seasonNumber);
			
			try {
				// format number of episode
				int n = Integer.parseInt(episode);
				
				if (episodeOffset == null)
					episodeOffset = n - 1;
				
				episode = numberFormat.format(n - episodeOffset);
			} catch (NumberFormatException e) {
				// episode may be "Pilot", "Special", "TV Movie" ...
				season = null;
			}
			
			episodes.add(new Episode(searchResult.getName(), season, episode, title));
		}
		
		return episodes;
	}
	

	@Override
	public URI getEpisodeListLink(SearchResult searchResult) {
		return getEpisodeListLink(searchResult, 0);
	}
	

	@Override
	public URI getEpisodeListLink(SearchResult searchResult, int season) {
		URL episodeListingUrl = ((HyperLink) searchResult).getURL();
		
		return URI.create(episodeListingUrl + "?season=" + season);
	}
	

	private URL getSearchUrl(String searchterm) throws UnsupportedEncodingException, MalformedURLException {
		String qs = URLEncoder.encode(searchterm, "UTF-8");
		String file = "/search.php?type=Search&stype=ajax_search&search_type=program&qs=" + qs;
		
		return new URL("http", host, file);
	}
	
	
	private class GetEpisodeList implements Callable<List<Episode>> {
		
		private final SearchResult searchResult;
		private final int season;
		
		
		public GetEpisodeList(SearchResult searchResult, int season) {
			this.searchResult = searchResult;
			this.season = season;
		}
		

		@Override
		public List<Episode> call() throws Exception {
			return getEpisodeList(searchResult, season);
		}
	}
	
}
