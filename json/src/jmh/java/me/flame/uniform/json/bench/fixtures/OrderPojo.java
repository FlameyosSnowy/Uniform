package me.flame.uniform.json.bench.fixtures;

import me.flame.uniform.core.annotations.SerializedObject;
import java.util.List;

@SerializedObject
public class OrderPojo {
    public int orderId;
    public String status;
    public double total;
    public String currency;
    public String placedAt;
    public List<OrderItemPojo> items;

    public OrderPojo() {}

    public OrderPojo(int orderId, String status, double total,
                     String currency, String placedAt, List<OrderItemPojo> items) {
        this.orderId = orderId; this.status = status; this.total = total;
        this.currency = currency; this.placedAt = placedAt; this.items = items;
    }
}