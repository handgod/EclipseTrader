/*
 * Copyright (c) 2004-2007 Marco Maccaferri and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Marco Maccaferri - initial API and implementation
 */

package net.sourceforge.eclipsetrader.yahoo;

import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import net.sourceforge.eclipsetrader.core.CorePlugin;
import net.sourceforge.eclipsetrader.core.INewsProvider;
import net.sourceforge.eclipsetrader.core.db.NewsItem;
import net.sourceforge.eclipsetrader.core.db.Security;
import net.sourceforge.eclipsetrader.news.NewsPlugin;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.IPreferenceStore;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.fetcher.impl.FeedFetcherCache;
import com.sun.syndication.fetcher.impl.HashMapFeedInfoCache;
import com.sun.syndication.fetcher.impl.HttpClientFeedFetcher;

public class NewsProvider implements Runnable, INewsProvider
{
    private Thread thread;
    private boolean stopping = false;
    private FeedFetcherCache feedInfoCache = HashMapFeedInfoCache.getInstance();
    private HttpClientFeedFetcher fetcher = new HttpClientFeedFetcher(feedInfoCache);
    static private List oldItems = new ArrayList();
    private Log log = LogFactory.getLog(getClass());

    public NewsProvider()
    {
    }

    /* (non-Javadoc)
     * @see net.sourceforge.eclipsetrader.news.INewsProvider#start()
     */
    public void start()
    {
        if (thread == null)
        {
            stopping = false;
            thread = new Thread(this);
            thread.start();
        }
    }

    /* (non-Javadoc)
     * @see net.sourceforge.eclipsetrader.news.INewsProvider#stop()
     */
    public void stop()
    {
        stopping = true;
        if (thread != null)
        {
            try {
                thread.join();
            } catch (InterruptedException e) {
                log.error(e);
            }
            thread = null;
        }
    }

    /* (non-Javadoc)
     * @see net.sourceforge.eclipsetrader.news.INewsProvider#snapshot()
     */
    public void snapshot()
    {
        update();
    }

    /* (non-Javadoc)
     * @see net.sourceforge.eclipsetrader.news.INewsProvider#snapshot(net.sourceforge.eclipsetrader.core.db.Security)
     */
    public void snapshot(Security security)
    {
        try {
            update(new URL("http://finance.yahoo.com/rss/headline?s=" + security.getCode().toLowerCase()), security);
        } catch(Exception e) {
            log.error(e);
        }
    }

    /* (non-Javadoc)
     * @see java.lang.Runnable#run()
     */
    public void run()
    {
        long nextRun = System.currentTimeMillis() + 2 * 1000;

        while(!stopping)
        {
            if (System.currentTimeMillis() >= nextRun)
            {
                update();
                int interval = NewsPlugin.getDefault().getPreferenceStore().getInt(NewsPlugin.PREFS_UPDATE_INTERVAL);
                nextRun = System.currentTimeMillis() + interval * 60 * 1000;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.error(e);
                break;
            }
        }
        
        thread = null;
    }

    private void update()
    {
        Object[] o = oldItems.toArray();
        for (int i = 0; i < o.length; i++)
        {
            ((NewsItem)o[i]).setRecent(false);
            CorePlugin.getRepository().save((NewsItem)o[i]);
        }
        oldItems.clear();
        
        Job job = new Job("Yahoo! News") {
            protected IStatus run(IProgressMonitor monitor)
            {
                IPreferenceStore store = YahooPlugin.getDefault().getPreferenceStore();

                List urls = new ArrayList();
                try
                {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    Document document = builder.parse(FileLocator.openStream(YahooPlugin.getDefault().getBundle(), new Path("categories.xml"), false)); //$NON-NLS-1$

                    NodeList childNodes = document.getFirstChild().getChildNodes();
                    for (int i = 0; i < childNodes.getLength(); i++)
                    {
                        Node node = childNodes.item(i);
                        String nodeName = node.getNodeName();
                        if (nodeName.equalsIgnoreCase("category")) //$NON-NLS-1$
                        {
                            String id = ((Node)node).getAttributes().getNamedItem("id").getNodeValue(); //$NON-NLS-1$
                         
                            NodeList list = node.getChildNodes();
                            for (int x = 0; x < list.getLength(); x++)
                            {
                                Node item = list.item(x);
                                nodeName = item.getNodeName();
                                Node value = item.getFirstChild();
                                if (value != null)
                                {
                                    if (nodeName.equalsIgnoreCase("url")) //$NON-NLS-1$
                                    {
                                        if (store.getBoolean(id))
                                            urls.add(value.getNodeValue());
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error(e, e);
                }
                
                List securities = CorePlugin.getRepository().allSecurities();
                monitor.beginTask("Fetching Yahoo! News", securities.size() + urls.size());
                log.info("Start fetching Yahoo! News");

                for (Iterator iter = securities.iterator(); iter.hasNext(); )
                {
                    Security security = (Security) iter.next();
                    try {
                        String url = "http://finance.yahoo.com/rss/headline?s=" + security.getCode().toLowerCase(); //$NON-NLS-1$
                        monitor.subTask(url);
                        update(new URL(url), security);
                    } catch(Exception e) {
                        log.error(e, e);
                    }
                    monitor.worked(1);
                }
                for (Iterator iter = urls.iterator(); iter.hasNext(); )
                {
                    String url = (String) iter.next();
                    try {
                        monitor.subTask(url);
                        update(new URL(url));
                    } catch(Exception e) {
                        log.error(e, e);
                    }
                    monitor.worked(1);
                }

                monitor.done();
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }

    private void update(URL feedUrl)
    {
        update(feedUrl, null);
    }

    private void update(URL feedUrl, Security security)
    {
        Calendar limit = Calendar.getInstance();
        limit.add(Calendar.DATE, - CorePlugin.getDefault().getPreferenceStore().getInt(CorePlugin.PREFS_NEWS_DATE_RANGE));
        
        boolean subscribersOnly = YahooPlugin.getDefault().getPreferenceStore().getBoolean(YahooPlugin.PREFS_SHOW_SUBSCRIBERS_ONLY);
        log.debug(feedUrl);
        
        try {
            HttpClient client = new HttpClient();
            client.getHttpConnectionManager().getParams().setConnectionTimeout(5000);

            IPreferenceStore store = CorePlugin.getDefault().getPreferenceStore();
            if (store.getBoolean(CorePlugin.PREFS_ENABLE_HTTP_PROXY))
            {
                client.getHostConfiguration().setProxy(store.getString(CorePlugin.PREFS_PROXY_HOST_ADDRESS), store.getInt(CorePlugin.PREFS_PROXY_PORT_ADDRESS));
                if (store.getBoolean(CorePlugin.PREFS_ENABLE_PROXY_AUTHENTICATION))
                    client.getState().setProxyCredentials(AuthScope.ANY, new UsernamePasswordCredentials(store.getString(CorePlugin.PREFS_PROXY_USER), store.getString(CorePlugin.PREFS_PROXY_PASSWORD)));
            }

            SyndFeed feed = fetcher.retrieveFeed(feedUrl, client);
            for (Iterator iter = feed.getEntries().iterator(); iter.hasNext(); )
            {
                SyndEntry entry = (SyndEntry) iter.next();
                
                if (!subscribersOnly && entry.getTitle().indexOf("[$$]") != -1)
                    continue;
                
                NewsItem news = new NewsItem();
                news.setRecent(true);

                String title = entry.getTitle();
                if (title.endsWith(")"))
                {
                    int s = title.lastIndexOf('(');
                    if (s != -1)
                    {
                        news.setSource(title.substring(s + 1, title.length() - 1));
                        title = title.substring(0, s - 1).trim();
                    }
                }
                news.setTitle(title);
                
                news.setUrl(entry.getLink());
                
                Date entryDate = entry.getPublishedDate();
                if (entry.getUpdatedDate() != null)
                    entryDate = entry.getUpdatedDate();
                if (entryDate != null)
                {
                    Calendar date = Calendar.getInstance();
                    date.setTime(entryDate);
                    date.set(Calendar.SECOND, 0);
                    date.set(Calendar.MILLISECOND, 0);
                    news.setDate(date.getTime());
                }

                if (security != null)
                    news.addSecurity(security);
                
                if (!news.getDate().before(limit.getTime()) && !isDuplicated(news))
                {
                    log.trace(news.getTitle() + " (" + news.getSource() + ")");
                    CorePlugin.getRepository().save(news);
                    oldItems.add(news);
                }
            }
        } catch(Exception e) {
            log.error(e, e);
        }
    }
    
    boolean isDuplicated(NewsItem news)
    {
        NewsItem[] items = (NewsItem[])CorePlugin.getRepository().allNews().toArray(new NewsItem[0]);
        
        for (int i = 0; i < items.length; i++)
        {
            if (news.getTitle().equals(items[i].getTitle()) && news.getUrl().equals(items[i].getUrl()))
            {
                items[i].addSecurities(news.getSecurities());
                CorePlugin.getRepository().save(items[i]);
                return true;
            }
        }

        return false;
    }
}
