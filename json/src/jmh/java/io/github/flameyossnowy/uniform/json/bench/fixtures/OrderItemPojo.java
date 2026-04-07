package io.github.flameyossnowy.uniform.json.bench.fixtures;

import com.dslplatform.json.CompiledJson;
import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;


@CompiledJson
public class OrderItemPojo {
    public int itemId;
    public String sku;
    public String description;
    public int quantity;
    public double unitPrice;
    public double lineTotal;

    public OrderItemPojo() {}

    public OrderItemPojo(int itemId, String sku, String description,
                         int quantity, double unitPrice, double lineTotal) {
        this.itemId = itemId; this.sku = sku; this.description = description;
        this.quantity = quantity; this.unitPrice = unitPrice; this.lineTotal = lineTotal;
    }
}