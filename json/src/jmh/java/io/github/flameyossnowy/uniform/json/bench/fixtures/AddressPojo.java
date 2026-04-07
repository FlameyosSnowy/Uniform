package io.github.flameyossnowy.uniform.json.bench.fixtures;

import com.dslplatform.json.CompiledJson;
import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;


@CompiledJson
public class AddressPojo {
    public String street;
    public String city;
    public String state;
    public String zip;
    public String country;
    public double lat;
    public double lon;

    public AddressPojo() {}

    public AddressPojo(String street, String city, String state,
                       String zip, String country, double lat, double lon) {
        this.street = street; this.city = city; this.state = state;
        this.zip = zip; this.country = country; this.lat = lat; this.lon = lon;
    }
}