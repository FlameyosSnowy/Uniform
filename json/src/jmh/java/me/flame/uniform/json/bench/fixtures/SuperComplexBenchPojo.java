package me.flame.uniform.json.bench.fixtures;

import com.dslplatform.json.CompiledJson;
import me.flame.uniform.core.annotations.SerializedObject;
import java.util.List;

@SerializedObject
@CompiledJson
public class SuperComplexBenchPojo {
    public int id;
    public String name;
    public String email;
    public String createdAt;
    public boolean active;
    public double score;
    public int age;
    public String role;
    public String bio;
    public String avatarUrl;
    public AddressPojo address;
    public List<TagPojo> tags;
    public List<SimpleBenchPojo> friends;
    public MetadataPojo metadata;
    public List<OrderPojo> orders;

    public SuperComplexBenchPojo() {}

    public SuperComplexBenchPojo(int id, String name, String email, String createdAt,
                                  boolean active, double score, int age, String role,
                                  String bio, String avatarUrl, AddressPojo address,
                                  List<TagPojo> tags, List<SimpleBenchPojo> friends,
                                  MetadataPojo metadata, List<OrderPojo> orders) {
        this.id = id; this.name = name; this.email = email; this.createdAt = createdAt;
        this.active = active; this.score = score; this.age = age; this.role = role;
        this.bio = bio; this.avatarUrl = avatarUrl; this.address = address;
        this.tags = tags; this.friends = friends; this.metadata = metadata;
        this.orders = orders;
    }
}