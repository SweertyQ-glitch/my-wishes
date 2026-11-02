package com.example.wishlist;

import org.json.JSONException;
import org.json.JSONObject;

/** Модель данных: одно желание. */
public class Wish {
    public final String id;
    public String name;
    public String price;        // пустая строка = цена не указана
    public String link;
    public String description;
    public String photo;        // data:image/jpeg;base64,... или пустая строка
    public final long createdAt;

    public Wish(String id, String name, String price, String link,
                String description, String photo, long createdAt) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.link = link;
        this.description = description;
        this.photo = photo;
        this.createdAt = createdAt;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("price", price);
        o.put("link", link);
        o.put("description", description);
        o.put("photo", photo);
        o.put("createdAt", createdAt);
        return o;
    }

    public static Wish fromJson(JSONObject o) {
        return new Wish(
                o.optString("id"),
                o.optString("name"),
                o.optString("price"),
                o.optString("link"),
                o.optString("description"),
                o.optString("photo"),
                o.optLong("createdAt", System.currentTimeMillis()));
    }
}
