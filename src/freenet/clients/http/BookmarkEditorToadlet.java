package freenet.clients.http;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import freenet.node.Node;

import freenet.clients.http.bookmark.Bookmark;
import freenet.clients.http.bookmark.BookmarkItem;
import freenet.clients.http.bookmark.BookmarkItems;
import freenet.clients.http.bookmark.BookmarkCategory;
import freenet.clients.http.bookmark.BookmarkCategories;
import freenet.clients.http.bookmark.BookmarkManager;

import freenet.node.useralerts.UserAlertManager;
import freenet.keys.FreenetURI;
import freenet.node.NodeClientCore;
import freenet.client.HighLevelSimpleClient;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

public class BookmarkEditorToadlet extends Toadlet {

	private static final int MAX_ACTION_LENGTH = 20;
	private static final int MAX_KEY_LENGTH = QueueToadlet.MAX_KEY_LENGTH;
	private static final int MAX_NAME_LENGTH = 500;
	private static final int MAX_BOOKMARK_PATH_LENGTH = 10 * MAX_NAME_LENGTH;

	private final Node node;
	private final NodeClientCore core;
	private final BookmarkManager bookmarkManager;

	
	BookmarkEditorToadlet(HighLevelSimpleClient client, Node node)
	{
		super(client);
		this.node = node;
		this.core = node.clientCore;
		this.bookmarkManager = core.bookmarkManager;		
	}
	
	private void addCategoryToList(BookmarkCategory cat, String path, HTMLNode list)
	{
		BookmarkItems items = cat.getItems();
		
		for(int i = 0; i < items.size(); i++) {

			String itemPath = path + items.get(i).getName();
			HTMLNode li = new HTMLNode("li", "class","item" , items.get(i).getName());

			HTMLNode actions = new HTMLNode("span", "class", "actions");
			actions.addChild("a", "href", "?action=edit&bookmark=" + itemPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/edit.png", "edit", "Edit"});
			
			actions.addChild("a", "href", "?action=del&bookmark=" + itemPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/delete.png", "delete", "Delete"});
			
			if(i != 0)
				actions.addChild("a", "href", "?action=up&bookmark=" + itemPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/go-up.png", "up", "Go up"});
			
			if(i != items.size()-1)
				actions.addChild("a", "href", "?action=down&bookmark=" + itemPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/go-down.png", "down", "Go down"});
			
			li.addChild(actions);
			list.addChild(li);
		}

		BookmarkCategories cats = cat.getSubCategories();
		for(int i = 0; i < cats.size(); i++) {

			String catPath = path + cats.get(i).getName() + "/";
			
			HTMLNode subCat = list.addChild("li", "class", "cat", cats.get(i).getName());

			HTMLNode actions = new HTMLNode("span", "class", "actions");
			
			actions.addChild("a", "href", "?action=edit&bookmark=" + catPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/edit.png", "edit", "Edit"});
			
			actions.addChild("a", "href", "?action=del&bookmark=" + catPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/delete.png", "delete", "Delete"});
			
			actions.addChild("a", "href", "?action=addItem&bookmark=" + catPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/bookmark-new.png", "add bookmark", "Add bookmark"});
			
			actions.addChild("a", "href", "?action=addCat&bookmark=" + catPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/folder-new.png", "add category", "Add category"});
			
			if(i != 0)
				actions.addChild("a", "href", "?action=up&bookmark=" + catPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/go-up.png", "up", "Go up"});
			
			if(i != cats.size() -1)
				actions.addChild("a", "href", "?action=down&bookmark=" + catPath).addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/go-down.png", "down", "Go down"});
			
			subCat.addChild(actions);
			if(cats.get(i).size() != 0)
				addCategoryToList(cats.get(i), catPath, list.addChild("li").addChild("ul"));
		}
	}

	public HTMLNode getBookmarksList()
	{
		HTMLNode bookmarks = new HTMLNode("ul", "id", "bookmarks");
		
		HTMLNode root = bookmarks.addChild("li", "class", "cat,root", "/");
		HTMLNode actions = new HTMLNode("span", "class", "actions");
		actions.addChild("a", "href", "?action=addItem&bookmark=/").addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/bookmark-new.png", "add bookmark", "Add bookmark"});
		actions.addChild("a", "href", "?action=addCat&bookmark=/").addChild("img", new String[] {"src", "alt", "title"}, new String[] {"/static/icon/folder-new.png", "add category", "Add category"});
		root.addChild(actions);
		
		addCategoryToList(bookmarkManager.getMainCategory(), "/", root.addChild("li").addChild("ul"));
		
		return bookmarks;
	}
	
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) 
			throws ToadletContextClosedException, IOException 
	{
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode("Bookmark Editor", ctx);
		HTMLNode content = ctx.getPageMaker().getContentNode(pageNode);
		
		if (req.getParam("action").length() > 0 && req.getParam("bookmark").length() > 0) {
			String action = req.getParam("action");
			String bookmarkPath = req.getParam("bookmark");
			Bookmark bookmark;
			
			if (bookmarkPath.endsWith("/"))
				bookmark = bookmarkManager.getCategoryByPath(bookmarkPath);
			else
				bookmark = bookmarkManager.getItemByPath(bookmarkPath);
				
			if(bookmark == null) {
				HTMLNode errorBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-error", "Error"));
				errorBox.addChild("#", "Bookmark \""+ bookmarkPath + "\" does not exists.");
			} else {
			
			if(action.equals("del")){
				
				HTMLNode infoBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-query", "Delete " + (bookmark instanceof BookmarkItem ? "bookmark" : "category")));
				
				String query = "Are you sure you wish to delete " + bookmarkPath;
				if(bookmark instanceof BookmarkCategory)
					query+= " and all its children";
				query+= " ?";
				infoBox.addChild("p").addChild("#", query);
				
				HTMLNode confirmForm = ctx.addFormChild(infoBox.addChild("p"), "", "confirmDeleteForm");
				confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "bookmark", bookmarkPath});
				confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", "Cancel" });
				confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "confirmdelete", "Delete" });
				
				
			} else if (action.equals("edit") || action.equals("addItem") || action.equals("addCat")) {
				
				String header;
				if(action.equals("edit"))
					header = "Edit "+(bookmark instanceof BookmarkItem ? "bookmark" : "category");
				else if(action.equals("addItem"))
					header = "Add a new bookmark";
				else
					header = "Add a new category";
				
				HTMLNode actionBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-query", header));
				
				HTMLNode form = ctx.addFormChild(actionBox, "", "editBookmarkForm");
				
				form.addChild("label", "for", "name", "Name : ");
				form.addChild("input", new String[]{"type", "id", "name", "size", "value"}, new String []{"text", "name", "name", "20", action.equals("edit")?bookmark.getName():""});
				
				form.addChild("br");
				if ((action.equals("edit") && bookmark instanceof BookmarkItem) || action.equals("addItem")) {
					String key = (action.equals("edit") ? ((BookmarkItem) bookmark).getKey() : "");
					form.addChild("label", "for", "key", "Key : ");
					form.addChild("input", new String[]{"type", "id", "name", "size", "value"}, new String []{"text", "key", "key", "50", key});
				}
				
				form.addChild("input", new String[] {"type", "name", "value"}, new String[] {"hidden", "bookmark",bookmarkPath});
				
				form.addChild("input", new String[] {"type", "name", "value"}, new String[] {"hidden", "action",req.getParam("action")});
				
				form.addChild("br");
				form.addChild("input", new String[]{"type", "value"}, new String[]{"submit", "Save"});
			} else if (action.equals("up") || action.equals("down")) {
				if(action.equals("up"))
					bookmarkManager.moveBookmarkUp(bookmarkPath, true);
				else
					bookmarkManager.moveBookmarkDown(bookmarkPath, true);
			}
			}
			
		}
		
		HTMLNode bookmarksBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-normal", "My Bookmarks"));
		bookmarksBox.addChild(getBookmarksList());

		this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
	}

	
	public void handlePost(URI uri, HTTPRequest req, ToadletContext ctx) 
		throws ToadletContextClosedException, IOException 
	{
		HTMLNode pageNode = ctx.getPageMaker().getPageNode("Bookmark Editor", ctx);
		HTMLNode content = ctx.getPageMaker().getContentNode(pageNode);
		
		String passwd = req.getPartAsString("formPassword", 32);
		boolean noPassword = (passwd == null) || !passwd.equals(core.formPassword);
		if(noPassword) 
			return;
		
		
		String bookmarkPath = req.getPartAsString("bookmark", MAX_BOOKMARK_PATH_LENGTH);
		try {
			
			Bookmark bookmark;
			if(bookmarkPath.endsWith("/"))
				bookmark = bookmarkManager.getCategoryByPath(bookmarkPath);
			else
				bookmark = bookmarkManager.getItemByPath(bookmarkPath);
			
			String action = req.getPartAsString("action", MAX_ACTION_LENGTH);
			
			if (req.isPartSet("confirmdelete")) {
				bookmarkManager.removeBookmark(bookmarkPath, true);
				HTMLNode successBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-success", "Delete succeeded"));
				successBox.addChild("p", "The bookmark has been deleted successfully");
				
			} else if (action.equals("edit") || action.equals("addItem") || action.equals("addCat")) {
				
				String name = "unnamed";
				if (req.getPartAsString("name", MAX_NAME_LENGTH).length() > 0)
					name = req.getPartAsString("name", MAX_NAME_LENGTH);
				
				if(action.equals("edit")) {
					bookmarkManager.renameBookmark(bookmarkPath, name);
					if(bookmark instanceof BookmarkItem)
						((BookmarkItem) bookmark).setKey(new FreenetURI(req.getPartAsString("key", MAX_KEY_LENGTH)));
					
					HTMLNode successBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-success", "Modifications saved"));
					successBox.addChild("p", "The changes has been saved successfully");
						
				} else if (action.equals("addItem") || action.equals("addCat")) {
					
					Bookmark newBookmark;
					if(action.equals("addItem")) {
						FreenetURI key = new FreenetURI(req.getPartAsString("key", MAX_KEY_LENGTH));
						newBookmark = new BookmarkItem(key, name, core.alerts);
					} else
						newBookmark = new BookmarkCategory(name);
					
					bookmarkManager.addBookmark(bookmarkPath, newBookmark, true);
					
					HTMLNode successBox =  content.addChild(ctx.getPageMaker().getInfobox("infobox-success", "New bookmark added"));
					successBox.addChild("p", "The new bookmark has been added successfully");
					
				}
				
			}

		} catch (NullPointerException npo) {
			HTMLNode errorBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-error", "Error"));
			errorBox.addChild("#", "Bookmark \""+ bookmarkPath + "\" does not exists.");
		} catch (MalformedURLException mue) {
			HTMLNode errorBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-error", "Invalid key"));
			errorBox.addChild("#", "Invalid Freenet Key");
		}
		HTMLNode bookmarksBox = content.addChild(ctx.getPageMaker().getInfobox("infobox-normal", "My Bookmarks"));
		bookmarksBox.addChild(getBookmarksList());
		
		this.writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
	}
	
	public String supportedMethods()
	{
		return "GET, POST";
	}
}
