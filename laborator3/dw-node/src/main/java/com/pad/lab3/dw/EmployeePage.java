package com.pad.lab3.dw;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.List;

@JacksonXmlRootElement(localName = "employees")
public class EmployeePage {
    private long total;
    private int offset;
    private int limit;
    @JacksonXmlElementWrapper(localName = "items")
    @JacksonXmlProperty(localName = "employee")
    private List<Employee> items;

    public EmployeePage() {}

    public EmployeePage(long total, int offset, int limit, List<Employee> items) {
        this.total = total;
        this.offset = offset;
        this.limit = limit;
        this.items = items;
    }

    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
    public List<Employee> getItems() { return items; }
    public void setItems(List<Employee> items) { this.items = items; }
}
