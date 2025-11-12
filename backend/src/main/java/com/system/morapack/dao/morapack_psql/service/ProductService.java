package com.system.morapack.dao.morapack_psql.service;

import com.system.morapack.dao.morapack_psql.model.Product;
import com.system.morapack.dao.morapack_psql.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
@Service
@RequiredArgsConstructor
public class ProductService {

  private final ProductRepository productRepository;

  public Product getProduct(Integer id) {
    return productRepository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException("ProductSchema not found with id: " + id));
  }

  public List<Product> fetchProducts(List<Integer> ids) {
    if (ids == null || ids.isEmpty()) {
      return productRepository.findAll();
    }
    return productRepository.findByIdIn(ids);
  }

  public List<Product> searchProductsByName(String name) {
    return productRepository.findByNameContainingIgnoreCase(name);
  }

  public List<Product> getProductsByOrder(Integer orderId) {
    return productRepository.findByOrder_Id(orderId);
  }

  public Product createProduct(Product product) {
    if (productRepository.existsByName(product.getName())) {
      throw new IllegalArgumentException("ProductSchema already exists: " + product.getName());
    }
    return productRepository.save(product);
  }

  public List<Product> bulkCreateProducts(List<Product> products) {
    return productRepository.saveAll(products);
  }

  public Product save(Product product) {
    return productRepository.save(product);
  }

  public Product updateProduct(Integer id, Product updates) {
    Product product = getProduct(id);

    if (updates.getName() != null)
      product.setName(updates.getName());
    if (updates.getWeight() != null)
      product.setWeight(updates.getWeight());
    if (updates.getVolume() != null)
      product.setVolume(updates.getVolume());

    return productRepository.save(product);
  }

  public void deleteProduct(Integer id) {
    if (!productRepository.existsById(id)) {
      throw new EntityNotFoundException("ProductSchema not found with id: " + id);
    }
    productRepository.deleteById(id);
  }

  @Transactional
  public void bulkDeleteProducts(List<Integer> ids) {
    productRepository.deleteAllByIdIn(ids);
  }
}
