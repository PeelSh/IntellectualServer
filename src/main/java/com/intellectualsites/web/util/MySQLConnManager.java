package com.intellectualsites.web.util;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Created by Liam on 07/07/2015.
 */
public class MySQLConnManager {
    private String host;
    private String port;
    private String db;
    private String user;
    private String pass;

    private Connection conn;

    public MySQLConnManager(String host, String port, String db, String user, String pass) {
        this.host = host;
        this.port = port;
        this.db = db;
        this.user = user;
        this.pass = pass;
        init();
    }

    public Connection getConnection() {
        return conn;
    }

    private void init() {
        try {
            conn = DriverManager.getConnection()
        }
    }
}
