package io.github.flameyossnowy.uniform.json.bench.fixtures;

import com.dslplatform.json.CompiledJson;
import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;
import java.util.List;

@SerializedObject
@CompiledJson
public class MediumBenchPojo {
    public int id;
    public String name;
    public String email;
    public AddressPojo address;
    public List<OrderItemPojo> items;
    public String status;
    public String createdAt;
    public String notes;

    public MediumBenchPojo() {}

    public MediumBenchPojo(int id, String name, String email, AddressPojo address,
                            List<OrderItemPojo> items, String status,
                            String createdAt, String notes) {
        this.id        = id;
        this.name      = name;
        this.email     = email;
        this.address   = address;
        this.items     = items;
        this.status    = status;
        this.createdAt = createdAt;
        this.notes     = notes;
    }
}