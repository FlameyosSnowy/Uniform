package me.flame.uniform.json.codegen.fixtures;

import me.flame.uniform.core.annotations.SerializedObject;

import java.util.List;

@SerializedObject
public class User {
    public int id;
    public String username;
    public String email;
    public int age;
    public boolean active;
    public double score;
    public Address address;
    public Metadata metadata;

    public User(int id, String username, String email, boolean active, double score, int age, Address address, Metadata metadata) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.active = active;
        this.score = score;
        this.age = age;
        this.address = address;
        this.metadata = metadata;
    }

    public User() {}

    public record Address(String street, String city, String zip, String country) {}
    public record Metadata(long createdAt, long lastLogin, int loginCount) {}

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof User user)) return false;

        return id == user.id && age == user.age && active == user.active && Double.compare(score, user.score) == 0 && username.equals(user.username) && email.equals(user.email) && address.equals(user.address) && metadata.equals(user.metadata);
    }

    @Override
    public int hashCode() {
        int result = id;
        result = 31 * result + username.hashCode();
        result = 31 * result + email.hashCode();
        result = 31 * result + age;
        result = 31 * result + Boolean.hashCode(active);
        result = 31 * result + Double.hashCode(score);
        result = 31 * result + address.hashCode();
        result = 31 * result + metadata.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "User{" +
            "id=" + id +
            ", username='" + username + '\'' +
            ", email='" + email + '\'' +
            ", age=" + age +
            ", active=" + active +
            ", score=" + score +
            ", address=" + address +
            ", metadata=" + metadata +
            '}';
    }
}